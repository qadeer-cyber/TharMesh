// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer / Qadeer Cyber. All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.identity

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Per-device signing identity. Stage 4.6 requirement: every bundle we originate is
 * signed by the device's private key, and every bundle we receive is verified
 * against a peer's public key (Trust-On-First-Use, see [com.tharmesh.identity.PeerTrustStore]).
 *
 * Algorithm: ECDSA over curve secp256r1 (NIST P-256), digest SHA-256. Chosen over
 * Ed25519 because Ed25519 JDK support requires API 30+ AND a matching Conscrypt
 * build; P-256 is available since Android API 1 via the stock JCE provider, uses
 * zero extra dependencies, and yields ~71-byte DER signatures which are fine on
 * top of our ~200-byte average BUNDLE frame.
 *
 * Keys are serialized as:
 *   - Private: PKCS#8 DER, Base64 (persisted in SharedPreferences).
 *   - Public:  X.509 SubjectPublicKeyInfo DER, Base64 (sent on the wire inside the
 *     BUNDLE frame so receivers can verify the signature without prior exchange).
 *
 * Fingerprint: SHA-256(publicKeyDer).hex, first 16 chars — used for log lines only.
 */
class CryptoIdentity private constructor(
    private val keyPair: KeyPair
) {

    /** Base64(X.509 DER) of the public key. Safe to share on the wire. */
    val publicKeyBase64: String
        get() = Base64Url.encode(keyPair.public.encoded)

    /** Short, human-readable fingerprint for log lines. Not cryptographically canonical. */
    val fingerprint: String
        get() = fingerprintOf(publicKeyBase64)

    /** Base64(PKCS#8 DER) of the private key. Secret — only persisted locally. */
    val privateKeyBase64: String
        get() = Base64Url.encode(keyPair.private.encoded)

    /**
     * Sign arbitrary bytes with the device's private key. Returns Base64(DER signature).
     * Callers should pass the canonical signing blob produced by
     * [canonicalBundleBytes] to avoid signature-format drift across versions.
     */
    fun sign(data: ByteArray): String {
        val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
        sig.initSign(keyPair.private)
        sig.update(data)
        return Base64Url.encode(sig.sign())
    }

    companion object {
        /**
         * JCE standard name. SHA-256 with ECDSA, DER-encoded output. Works on every
         * Android API level we target (minSdk 21 via our build.gradle).
         */
        const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"
        const val KEY_ALGORITHM: String = "EC"
        const val CURVE: String = "secp256r1"
        /**
         * Field separator inside [canonicalBundleBytes]. 0x1F (ASCII Unit Separator)
         * is unambiguous: it never appears in UTF-8 text or base64, so two different
         * field tuples can never collide even with adversarial content.
         */
        private const val FIELD_SEP: Byte = 0x1F

        /**
         * Generate a fresh ECDSA P-256 keypair. Called once on first launch from
         * [com.tharmesh.data.UserPrefs.ensureIdentity]; subsequent launches deserialize
         * the persisted key material via [fromBase64].
         */
        fun generate(): CryptoIdentity {
            val gen = KeyPairGenerator.getInstance(KEY_ALGORITHM)
            gen.initialize(ECGenParameterSpec(CURVE))
            return CryptoIdentity(gen.generateKeyPair())
        }

        /**
         * Reconstruct a [CryptoIdentity] from persisted base64-encoded PKCS#8 and X.509
         * DER blobs. Returns null if either input fails to decode — callers should
         * treat that as "no identity on disk" and call [generate] to mint a new one.
         */
        fun fromBase64(privateKeyBase64: String, publicKeyBase64: String): CryptoIdentity? {
            return try {
                val privBytes = Base64Url.decode(privateKeyBase64) ?: return null
                val pubBytes = Base64Url.decode(publicKeyBase64) ?: return null
                val kf = KeyFactory.getInstance(KEY_ALGORITHM)
                val priv: PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val pub: PublicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                CryptoIdentity(KeyPair(pub, priv))
            } catch (ignored: Throwable) {
                null
            }
        }

        /**
         * Verify [signatureBase64] against [data] using the sender's [publicKeyBase64].
         * Returns false on any failure path — malformed key, malformed signature,
         * tampered data, or JCE exception. Does NOT throw.
         */
        fun verify(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean {
            if (publicKeyBase64.isEmpty() || signatureBase64.isEmpty()) return false
            return try {
                val pubBytes = Base64Url.decode(publicKeyBase64) ?: return false
                val sigBytes = Base64Url.decode(signatureBase64) ?: return false
                val kf = KeyFactory.getInstance(KEY_ALGORITHM)
                val pub = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
                sig.initVerify(pub)
                sig.update(data)
                sig.verify(sigBytes)
            } catch (ignored: Throwable) {
                false
            }
        }

        /**
         * Canonical bytes signed/verified on every bundle. Fields included are exactly
         * the end-to-end invariants: bundleId, srcId, destId, payloadCiphertext,
         * ttlUntil. Explicitly NOT included:
         *   - hopsLeft: decremented on each hop; signing it would invalidate the sig
         *     at every relay.
         *   - status: mutates per hop (PENDING → FORWARDED → DELIVERED_FINAL).
         *   - signature: trivially.
         *   - srcPubKey: its authenticity comes from the TOFU match in
         *     [PeerTrustStore], not from self-signing.
         *
         * Fields are joined with [FIELD_SEP] so any two distinct field tuples produce
         * distinct signing blobs (no collision via trailing field boundary ambiguity).
         */
        fun canonicalBundleBytes(
            bundleId: String,
            srcId: String,
            destId: String,
            payloadCiphertext: String,
            ttlUntil: Long
        ): ByteArray {
            val parts = listOf(bundleId, srcId, destId, payloadCiphertext, ttlUntil.toString())
            val out = java.io.ByteArrayOutputStream()
            parts.forEachIndexed { idx, s ->
                if (idx > 0) out.write(FIELD_SEP.toInt())
                out.write(s.toByteArray(Charsets.UTF_8))
            }
            return out.toByteArray()
        }

        fun fingerprintOf(publicKeyBase64: String): String {
            val raw = Base64Url.decode(publicKeyBase64) ?: return "invalid"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                hex.append(String.format("%02x", b.toInt() and 0xFF))
            }
            return hex.substring(0, 16)
        }
    }
}

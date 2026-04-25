// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.crypto

import com.tharmesh.identity.Base64Url
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Derives and caches per-peer AES-256 symmetric keys over static ECDH on the
 * device's P-256 signing keypair + the recipient's pinned public key.
 *
 * The same pair of devices always derives the same key, regardless of which
 * side originated it. This matches the symmetric usage of [SealedEnvelope] —
 * either party can seal/unseal for the other.
 *
 * Derivation: HKDF-SHA256 (RFC 5869) over the raw ECDH shared secret,
 * keyed by a deterministic `info` that binds both user IDs so a key derived
 * for peer A cannot be reused against peer B even if their public keys
 * somehow collided.
 *
 * No persistence. The cache is rebuilt on process start; lookups are cheap
 * (one HKDF run per peer) and the P-256 agreement itself takes ≈ 1 ms.
 *
 * Resolver: the caller supplies [resolvePublicKeyBase64] so this class can be
 * exercised with a simple Map in tests without pulling Room or the PeerTrustStore
 * into the test fixture.
 */
class PeerKeyRing(
    private val localPrivateKey: PrivateKey,
    private val localUserId: String,
    private val resolvePublicKeyBase64: (peerUserId: String) -> String?
) {

    private val cacheLock = Any()
    private val cache: MutableMap<String, SecretKey> = HashMap()

    /**
     * Returns the cached/derived AES-256 key for [peerUserId], or `null` if
     * we have no pinned public key for that peer. Callers should treat null
     * as "fall back to plaintext body on the wire" — this is the TOFU rollout
     * path for peers whose keys we haven't learnt yet.
     */
    fun keyFor(peerUserId: String): SecretKey? {
        synchronized(cacheLock) { cache[peerUserId] }?.let { return it }
        val peerPubB64 = resolvePublicKeyBase64(peerUserId) ?: return null
        val derived = deriveKey(peerPubB64, peerUserId) ?: return null
        synchronized(cacheLock) { cache[peerUserId] = derived }
        return derived
    }

    /**
     * Drop any cached key for [peerUserId]. Call after the peer's pinned key
     * is rotated (e.g. [PeerTrustStore.markVerified] replaces a previously
     * TOFU-pinned record) so the next send re-derives.
     */
    fun invalidate(peerUserId: String) {
        synchronized(cacheLock) { cache.remove(peerUserId) }
    }

    private fun deriveKey(peerPubBase64: String, peerUserId: String): SecretKey? {
        return try {
            val peerBytes = decode(peerPubBase64) ?: return null
            val kf = KeyFactory.getInstance("EC")
            val peerPub: PublicKey = kf.generatePublic(X509EncodedKeySpec(peerBytes))
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(localPrivateKey)
            ka.doPhase(peerPub, true)
            val sharedSecret = ka.generateSecret()
            val info = infoBytes(localUserId, peerUserId)
            val aesBytes = hkdfSha256(sharedSecret, SALT, info, AES_KEY_BYTES)
            SecretKeySpec(aesBytes, "AES")
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun decode(base64: String): ByteArray? = Base64Url.decode(base64)

    companion object {
        private const val AES_KEY_BYTES: Int = 32   // 256-bit AES key.
        private val SALT: ByteArray = "TharMesh/E1/v1".toByteArray(StandardCharsets.UTF_8)

        /**
         * Bind both user IDs into the KDF so the derived key is specific to the
         * (A, B) pair. User IDs are sorted lexicographically so either side
         * produces the same info bytes without coordinating roles.
         */
        internal fun infoBytes(aUserId: String, bUserId: String): ByteArray {
            val (lo, hi) = if (aUserId <= bUserId) aUserId to bUserId else bUserId to aUserId
            return ("TME1|" + lo + "|" + hi).toByteArray(StandardCharsets.UTF_8)
        }

        /**
         * RFC 5869 HKDF-Extract-then-Expand with SHA-256. Implemented locally
         * so TharMesh does not pull BouncyCastle or Conscrypt into the
         * toolchain; the primitives we need (Mac HmacSHA256) ship with every
         * JDK/Android API level we target.
         */
        internal fun hkdfSha256(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            outLen: Int
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            // Extract: PRK = HMAC(salt, IKM).
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            // Expand: T(0) = empty; T(i) = HMAC(PRK, T(i-1) | info | i) for i = 1..
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(outLen)
            var previous = ByteArray(0)
            var filled = 0
            var counter: Byte = 1
            while (filled < outLen) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter)
                previous = mac.doFinal()
                val take = minOf(previous.size, outLen - filled)
                System.arraycopy(previous, 0, out, filled, take)
                filled += take
                counter = (counter + 1).toByte()
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
            }
            return out
        }
    }
}

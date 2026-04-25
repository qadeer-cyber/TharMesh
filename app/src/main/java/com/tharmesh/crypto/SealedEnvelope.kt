// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.crypto

import com.tharmesh.identity.Base64Url
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wire format for end-to-end encrypted message payloads.
 *
 * Encoding: `TME1:<b64url(iv)>:<b64url(ciphertext||gcm_tag)>` as a single
 * UTF-8 string that replaces the plaintext body previously carried in
 * [com.tharmesh.dtn.MeshBundle.payloadCiphertext].
 *
 * The `TME1:` prefix is the version marker — receivers that recognise it
 * decrypt; older peers (and tests) that do not recognise it treat the whole
 * string as plaintext, which preserves backward compatibility during rollout.
 *
 * Crypto: AES-256 / GCM / 128-bit auth tag, per-envelope random 12-byte IV.
 * The symmetric key is derived elsewhere — see
 * [com.tharmesh.crypto.PeerKeyRing] — via static ECDH over the device's
 * P-256 signing keypair and the recipient's pinned public key.
 *
 * GCM IV uniqueness requirement: a single (key, IV) pair must never be used
 * twice. Using a cryptographic [SecureRandom] for the 12-byte IV on every
 * envelope gives collision probability ≈ n²/2¹⁰⁰ — negligible at the
 * message volumes TharMesh deals with.
 */
object SealedEnvelope {

    /** On-wire version marker. If a wire string starts with this, attempt decrypt. */
    const val PREFIX: String = "TME1:"

    private const val FIELD_SEP: Char = ':'
    private const val IV_LEN: Int = 12         // NIST-recommended GCM IV length.
    private const val TAG_BITS: Int = 128

    private val secureRandom: SecureRandom = SecureRandom()

    /** Wrap [plaintext] into a TME1 envelope using [key]. Returns the wire string. */
    fun seal(plaintext: String, key: SecretKey): String {
        val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(PREFIX.length + 24 + ct.size * 2)
        sb.append(PREFIX)
        sb.append(encodeUrl(iv))
        sb.append(FIELD_SEP)
        sb.append(encodeUrl(ct))
        return sb.toString()
    }

    /**
     * Unwrap a TME1-framed [wire] string using [key]. Returns the plaintext,
     * or `null` for any failure path — wrong prefix, malformed framing,
     * bad base64, wrong key, tampered ciphertext, or GCM auth-tag mismatch.
     * Callers MUST treat null as "could not decrypt, fall back to
     * plaintext-body behaviour" and NEVER as "silently drop".
     */
    fun unseal(wire: String, key: SecretKey): String? {
        if (!looksSealed(wire)) return null
        return try {
            val rest = wire.substring(PREFIX.length)
            val sep = rest.indexOf(FIELD_SEP)
            if (sep <= 0 || sep == rest.length - 1) return null
            val iv = decodeUrl(rest.substring(0, sep)) ?: return null
            if (iv.size != IV_LEN) return null
            val ct = decodeUrl(rest.substring(sep + 1)) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        } catch (ignored: Throwable) {
            null
        }
    }

    /** Cheap prefix test — does NOT validate the framing or decrypt. */
    fun looksSealed(wire: String): Boolean = wire.startsWith(PREFIX)

    // Base64 is delegated to the in-module [Base64Url] helper so unit tests run
    // without pulling Robolectric. The helper emits standard-alphabet base64
    // with `=` padding; neither appears in our TME1 payload (we use `:` as the
    // field separator and our own prefix marker) so there's no collision.

    private fun encodeUrl(bytes: ByteArray): String = Base64Url.encode(bytes)

    private fun decodeUrl(s: String): ByteArray? = Base64Url.decode(s)
}

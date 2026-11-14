// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side seal for identity key material. The recovery secret is not
 * written into the ciphertext. A server that stores [SealedIdentity] cannot
 * open it.
 *
 * New seals use scrypt (N = 2^14, r = 8, p = 1, 16 MiB) and a 128-bit secret.
 * The earlier PBKDF2-HMAC-SHA256 seal still opens so a blob from that path is
 * not discarded. New callers must use [seal], which does not take an iteration
 * count. PBKDF2 at 100 000 iterations is not memory-hard, and the 24-word
 * phrase generator it shipped with was about 36 bits.
 */
object IdentityRecovery {

    const val KDF_SCRYPT: String = "scrypt"
    const val KDF_PBKDF2: String = "PBKDF2-HMAC-SHA256"
    const val SCRYPT_N: Int = 16_384
    const val SCRYPT_R: Int = 8
    const val SCRYPT_P: Int = 1

    private const val KEY_BITS: Int = 256
    private const val IV_LEN: Int = 12
    private const val SALT_LEN: Int = 16
    private const val TAG_BITS: Int = 128
    private val AAD: ByteArray = "TharMesh-identity-v1".toByteArray(Charsets.UTF_8)

    data class SealedIdentity(
        val kdf: String,
        val iterations: Int,
        val saltB64: String,
        val ivB64: String,
        val ciphertextB64: String,
        val r: Int = SCRYPT_R,
        val p: Int = SCRYPT_P
    )

    /**
     * 128-bit secret, displayed as eight groups of four hex digits.
     * The groups are formatting. [normalize] accepts them with or without dashes.
     */
    fun generatePhrase(random: SecureRandom = SecureRandom()): String {
        val raw = ByteArray(16)
        random.nextBytes(raw)
        val hex = buildString(32) {
            for (b in raw) append("%02x".format(b.toInt() and 0xFF))
        }
        return hex.chunked(4).joinToString("-")
    }

    /** New seals. [n], [rParam], and [pParam] default to the production scrypt cost. */
    fun seal(
        plaintext: String,
        phrase: String,
        random: SecureRandom = SecureRandom(),
        n: Int = SCRYPT_N,
        rParam: Int = SCRYPT_R,
        pParam: Int = SCRYPT_P
    ): SealedIdentity {
        require(n >= 2 && n and (n - 1) == 0)
        require(rParam >= 1 && pParam >= 1)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = scryptKey(phrase, salt, n, rParam, pParam)
        return SealedIdentity(
            kdf = KDF_SCRYPT,
            iterations = n,
            saltB64 = Base64Url.encode(salt),
            ivB64 = Base64Url.encode(iv),
            ciphertextB64 = Base64Url.encode(encrypt(key, iv, plaintext)),
            r = rParam,
            p = pParam
        )
    }

    /**
     * Legacy seal kept so blobs produced with PBKDF2 can be reproduced in tests
     * and opened by [open]. Not used for new recovery blobs.
     */
    fun seal(
        plaintext: String,
        phrase: String,
        iterations: Int,
        random: SecureRandom
    ): SealedIdentity {
        require(iterations >= 1_000)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = pbkdf2Key(phrase, salt, iterations)
        return SealedIdentity(
            kdf = KDF_PBKDF2,
            iterations = iterations,
            saltB64 = Base64Url.encode(salt),
            ivB64 = Base64Url.encode(iv),
            ciphertextB64 = Base64Url.encode(encrypt(key, iv, plaintext)),
            r = 0,
            p = 0
        )
    }

    /** Returns the plaintext, or null when the secret or the framing is wrong. */
    fun open(sealed: SealedIdentity, phrase: String): String? {
        val salt = Base64Url.decode(sealed.saltB64) ?: return null
        val iv = Base64Url.decode(sealed.ivB64) ?: return null
        val ct = Base64Url.decode(sealed.ciphertextB64) ?: return null
        if (iv.size != IV_LEN || salt.isEmpty() || ct.isEmpty()) return null
        val key = when (sealed.kdf) {
            KDF_SCRYPT -> {
                if (sealed.iterations < 2 || sealed.r < 1 || sealed.p < 1) return null
                scryptKey(phrase, salt, sealed.iterations, sealed.r, sealed.p)
            }
            KDF_PBKDF2 -> {
                if (sealed.iterations < 1_000) return null
                pbkdf2Key(phrase, salt, sealed.iterations)
            }
            else -> return null
        }
        return try {
            String(decrypt(key, iv, ct), Charsets.UTF_8)
        } catch (ignored: Throwable) {
            null
        }
    }

    fun normalize(phrase: String): String {
        val collapsed = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
        return if (' ' in collapsed) collapsed else collapsed.replace("-", "")
    }

    private fun scryptKey(phrase: String, salt: ByteArray, n: Int, r: Int, p: Int): SecretKeySpec {
        val bytes = Scrypt.derive(
            normalize(phrase).toByteArray(Charsets.UTF_8),
            salt,
            n,
            r,
            p,
            KEY_BITS / 8
        )
        return SecretKeySpec(bytes, "AES")
    }

    private fun pbkdf2Key(phrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val bytes = Scrypt.pbkdf2(
            normalize(phrase).toByteArray(Charsets.UTF_8),
            salt,
            iterations,
            KEY_BITS / 8
        )
        return SecretKeySpec(bytes, "AES")
    }

    private fun encrypt(key: SecretKeySpec, iv: ByteArray, plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(key: SecretKeySpec, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ct)
    }
}

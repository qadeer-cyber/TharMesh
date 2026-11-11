// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side seal for an identity private key. The recovery phrase never
 * leaves the device as part of the ciphertext. A server that stores
 * [SealedIdentity] cannot open it.
 */
object IdentityRecovery {

    const val KDF: String = "PBKDF2-HMAC-SHA256"
    const val DEFAULT_ITERATIONS: Int = 100_000
    private const val KEY_BITS: Int = 256
    private const val IV_LEN: Int = 12
    private const val SALT_LEN: Int = 16
    private const val TAG_BITS: Int = 128
    private val AAD: ByteArray = "TharMesh-identity-v1".toByteArray(Charsets.UTF_8)

    private val words: List<String> = listOf(
        "amber", "banyan", "cedar", "desert", "ember", "falcon", "granite", "harbor",
        "indigo", "jasper", "kettle", "lotus", "marble", "north", "oasis", "pearl",
        "quartz", "river", "saffron", "thorn", "umber", "valley", "willow", "yarrow"
    )

    data class SealedIdentity(
        val kdf: String,
        val iterations: Int,
        val saltB64: String,
        val ivB64: String,
        val ciphertextB64: String
    )

    fun generatePhrase(random: SecureRandom = SecureRandom(), wordCount: Int = 8): String {
        val count = wordCount.coerceIn(6, 12)
        return (1..count).joinToString(" ") { words[random.nextInt(words.size)] }
    }

    fun seal(
        privateKeyBase64: String,
        phrase: String,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom()
    ): SealedIdentity {
        require(iterations >= 1_000) { "iterations" }
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = derive(phrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(privateKeyBase64.toByteArray(Charsets.UTF_8))
        return SealedIdentity(
            kdf = KDF,
            iterations = iterations,
            saltB64 = Base64Url.encode(salt),
            ivB64 = Base64Url.encode(iv),
            ciphertextB64 = Base64Url.encode(ct)
        )
    }

    /** Returns the private-key blob, or null when the phrase or framing is wrong. */
    fun open(sealed: SealedIdentity, phrase: String): String? {
        if (sealed.kdf != KDF || sealed.iterations < 1_000) return null
        val salt = Base64Url.decode(sealed.saltB64) ?: return null
        val iv = Base64Url.decode(sealed.ivB64) ?: return null
        val ct = Base64Url.decode(sealed.ciphertextB64) ?: return null
        if (iv.size != IV_LEN || salt.isEmpty() || ct.isEmpty()) return null
        return try {
            val key = derive(phrase, salt, sealed.iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun derive(phrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val normalized = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
        val spec = PBEKeySpec(normalized.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}

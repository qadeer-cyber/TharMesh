// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Framing shared by the Android Keystore wrap and by JVM tests.
 *
 * [LABEL_SOFTWARE_TEST_DOUBLE] marks seals made with a normal AES key.
 * Those tests are not Android Keystore and not hardware-backed.
 * The Keystore path lives in [AndroidAccountKeyBox] and returns false
 * instead of writing the private key in the clear when the Keystore throws.
 */
object AccountKeySeal {
    const val LABEL_SOFTWARE_TEST_DOUBLE: String = "NOT_ANDROID_KEYSTORE"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        return out
    }

    fun unpack(packed: ByteArray): Pair<ByteArray, ByteArray>? {
        if (packed.size <= IV_LEN) return null
        return packed.copyOfRange(0, IV_LEN) to packed.copyOfRange(IV_LEN, packed.size)
    }

    /** Software AES-GCM. Not Keystore. */
    fun sealSoftware(key: SecretKey, plaintext: String, random: SecureRandom = SecureRandom()): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64Url.encode(pack(iv, ct))
    }

    fun openSoftware(key: SecretKey, packedB64: String): String? {
        val packed = Base64Url.decode(packedB64) ?: return null
        val (iv, ct) = unpack(packed) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (ignored: Throwable) {
            null
        }
    }

    fun softwareKey(bytes: ByteArray = ByteArray(32) { (it + 3).toByte() }): SecretKey {
        return SecretKeySpec(bytes, "AES")
    }

    /**
     * What may be written after a Keystore attempt. A failure writes nothing.
     * A success writes the packed blob, which must not be the plaintext.
     */
    fun bytesToStore(keystoreSucceeded: Boolean, packed: ByteArray?, plaintext: String): String? {
        if (!keystoreSucceeded || packed == null) return null
        val encoded = Base64Url.encode(packed)
        if (encoded == plaintext || encoded.contains(plaintext)) return null
        return encoded
    }
}

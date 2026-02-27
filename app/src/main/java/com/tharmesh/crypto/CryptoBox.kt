package com.tharmesh.crypto

import android.util.Base64
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoBox {

    fun generateAesKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey()
    }

    fun encrypt(plainText: String, key: SecretKey, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val out = cipher.doFinal(plainText.toByteArray(Charset.forName("UTF-8")))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(cipherTextBase64: String, key: SecretKey, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val bytes = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        return String(cipher.doFinal(bytes), Charset.forName("UTF-8"))
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charset.forName("UTF-8")))
        val out = StringBuilder()
        for (b in digest) {
            out.append(String.format("%02x", b))
        }
        return out.toString()
    }
}

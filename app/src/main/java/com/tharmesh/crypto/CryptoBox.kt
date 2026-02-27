package com.tharmesh.crypto

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoBox {

    fun sealForReceiver(
        plainText: String,
        receiverPublicKeyBase64: String,
        senderPrivateKeyBase64: String,
        metadata: String
    ): String {
        val aesKey = generateAesKey()
        val iv = randomIv()
        val cipherText = aesEncrypt(plainText, aesKey, iv)

        val receiverKey = decodePublicKey(receiverPublicKeyBase64)
        val encryptedKey = rsaEncrypt(Base64.encodeToString(aesKey.encoded, Base64.NO_WRAP), receiverKey)

        val signInput = metadata + "|" + cipherText + "|" + Base64.encodeToString(iv, Base64.NO_WRAP) + "|" + encryptedKey
        val signature = rsaSign(signInput, decodePrivateKey(senderPrivateKeyBase64))

        val json = JSONObject()
        json.put("cipherText", cipherText)
        json.put("encryptedKey", encryptedKey)
        json.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        json.put("sig", signature)
        return json.toString()
    }

    fun openFromSender(
        sealedPayload: String,
        receiverPrivateKeyBase64: String,
        senderPublicKeyBase64: String,
        metadata: String
    ): String? {
        return try {
            val json = JSONObject(sealedPayload)
            val cipherText = json.getString("cipherText")
            val encryptedKey = json.getString("encryptedKey")
            val ivBase64 = json.getString("iv")
            val sig = json.getString("sig")
            val signInput = metadata + "|" + cipherText + "|" + ivBase64 + "|" + encryptedKey

            val verified = rsaVerify(signInput, sig, decodePublicKey(senderPublicKeyBase64))
            if (!verified) return null

            val aesKeyBase64 = rsaDecrypt(encryptedKey, decodePrivateKey(receiverPrivateKeyBase64))
            val aesKey = decodeAesKey(aesKeyBase64)
            aesDecrypt(cipherText, aesKey, Base64.decode(ivBase64, Base64.DEFAULT))
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun generateAesKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey()
    }

    private fun decodeAesKey(base64: String): SecretKey {
        val raw = Base64.decode(base64, Base64.DEFAULT)
        return javax.crypto.spec.SecretKeySpec(raw, "AES")
    }

    private fun aesEncrypt(plainText: String, key: SecretKey, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val out = cipher.doFinal(plainText.toByteArray(Charset.forName("UTF-8")))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun aesDecrypt(cipherTextBase64: String, key: SecretKey, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val bytes = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        return String(cipher.doFinal(bytes), Charset.forName("UTF-8"))
    }

    private fun rsaEncrypt(value: String, publicKey: PublicKey): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val out = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun rsaDecrypt(valueBase64: String, privateKey: PrivateKey): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val out = cipher.doFinal(Base64.decode(valueBase64, Base64.DEFAULT))
        return String(out, Charsets.UTF_8)
    }

    private fun rsaSign(value: String, privateKey: PrivateKey): String {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    private fun rsaVerify(value: String, signatureBase64: String, publicKey: PublicKey): Boolean {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update(value.toByteArray(Charsets.UTF_8))
        return signature.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
    }

    private fun decodePublicKey(base64: String): PublicKey {
        val keyFactory = KeyFactory.getInstance("RSA")
        val spec = X509EncodedKeySpec(Base64.decode(base64, Base64.DEFAULT))
        return keyFactory.generatePublic(spec)
    }

    private fun decodePrivateKey(base64: String): PrivateKey {
        val keyFactory = KeyFactory.getInstance("RSA")
        val spec = PKCS8EncodedKeySpec(Base64.decode(base64, Base64.DEFAULT))
        return keyFactory.generatePrivate(spec)
    }

    private fun randomIv(): ByteArray {
        val bytes = ByteArray(12)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
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

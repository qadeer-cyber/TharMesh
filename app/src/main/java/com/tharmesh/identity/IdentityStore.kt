package com.tharmesh.identity

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class IdentityStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureIdentity(defaultName: String = "TharMesh User"): LocalIdentity {
        val existingUserId = prefs.getString(KEY_USER_ID, null)
        val existingName = prefs.getString(KEY_NAME, null)
        val existingPub = prefs.getString(KEY_PUB, null)
        val existingPriv = prefs.getString(KEY_PRIV, null)

        if (!existingUserId.isNullOrBlank() && !existingName.isNullOrBlank() && !existingPub.isNullOrBlank() && !existingPriv.isNullOrBlank()) {
            return LocalIdentity(
                userId = existingUserId,
                name = existingName,
                publicKeyBase64 = existingPub,
                privateKeyBase64 = existingPriv
            )
        }

        val pair = generateRsaKeyPair()
        val publicKeyBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        val privateKeyBase64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
        val userId = toUserId(publicKeyBase64)
        val name = existingName ?: defaultName

        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_NAME, name)
            .putString(KEY_PUB, publicKeyBase64)
            .putString(KEY_PRIV, privateKeyBase64)
            .apply()

        return LocalIdentity(
            userId = userId,
            name = name,
            publicKeyBase64 = publicKeyBase64,
            privateKeyBase64 = privateKeyBase64
        )
    }

    fun readIdentityOrNull(): LocalIdentity? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val pub = prefs.getString(KEY_PUB, null) ?: return null
        val priv = prefs.getString(KEY_PRIV, null) ?: return null
        return LocalIdentity(userId, name, pub, priv)
    }

    fun getKeyPairOrNull(): KeyPair? {
        return try {
            val identity = readIdentityOrNull() ?: return null
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(Base64.decode(identity.publicKeyBase64, Base64.DEFAULT)))
            val privateKey: PrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(identity.privateKeyBase64, Base64.DEFAULT)))
            KeyPair(publicKey, privateKey)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun generateRsaKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    private fun toUserId(publicKeyBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBase64.toByteArray(Charsets.UTF_8))
        val base32 = toBase32(digest)
        return base32.take(12)
    }

    private fun toBase32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 31
                bitsLeft -= 5
                out.append(alphabet[index])
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 31
            out.append(alphabet[index])
        }
        return out.toString()
    }

    companion object {
        private const val PREFS = "identity_store"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NAME = "name"
        private const val KEY_PUB = "public_key"
        private const val KEY_PRIV = "private_key"
    }
}

data class LocalIdentity(
    val userId: String,
    val name: String,
    val publicKeyBase64: String,
    val privateKeyBase64: String
)

package com.tharmesh.identity

import android.content.Context
import android.util.Base64
import com.tharmesh.data.UserPrefs
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

class IdentityStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureIdentity(): LocalIdentity {
        val existingPub = prefs.getString(KEY_PUB, null)
        val existingPriv = prefs.getString(KEY_PRIV, null)
        val profile = UserPrefs.ensureProfile(context)

        if (!existingPub.isNullOrBlank() && !existingPriv.isNullOrBlank()) {
            return LocalIdentity(
                userId = profile.userId,
                name = profile.username,
                publicKeyBase64 = existingPub,
                privateKeyBase64 = existingPriv
            )
        }

        val pair = generateKeyPair()
        val pub = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        val priv = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PUB, pub).putString(KEY_PRIV, priv).apply()
        return LocalIdentity(
            userId = stableUserIdFromPublicKey(pub),
            name = profile.username,
            publicKeyBase64 = pub,
            privateKeyBase64 = priv
        )
    }

    fun getKeyPairOrNull(): KeyPair? {
        return try {
            val pub = prefs.getString(KEY_PUB, null) ?: return null
            val priv = prefs.getString(KEY_PRIV, null) ?: return null
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.DEFAULT)))
            val privateKey: PrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.DEFAULT)))
            KeyPair(publicKey, privateKey)
        } catch (ignored: Throwable) {
            null
        }
    }

    fun stableUserIdFromPublicKey(publicKeyBase64: String): String {
        val src = publicKeyBase64.replace("=", "").uppercase(Locale.US)
        return "U" + src.take(11)
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        return generator.generateKeyPair()
    }

    companion object {
        private const val PREFS = "identity_store"
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

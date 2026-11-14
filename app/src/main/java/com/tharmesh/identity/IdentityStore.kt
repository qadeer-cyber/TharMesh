package com.tharmesh.identity

import android.content.Context
import android.util.Base64
import com.tharmesh.data.UserPrefs
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

class IdentityStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * QR identity. Uses the mesh signing key from [UserPrefs] when one exists.
     * A different key already in this store is archived under [KEY_ARCHIVED_PUB]
     * and [KEY_ARCHIVED_PRIV] and is not deleted. A store key is imported into
     * UserPrefs only when UserPrefs has no signing key yet.
     */
    fun ensureIdentity(): LocalIdentity {
        val profile = UserPrefs.readProfile(context)
        val mesh = UserPrefs.readIdentity(context)?.let {
            QrIdentityAlign.KeyMaterial(it.publicKeyBase64, it.privateKeyBase64)
        }
        val store = keyMaterial(KEY_PUB, KEY_PRIV)
        val decision = QrIdentityAlign.decide(mesh, store)
        if (decision.archiveStore != null) {
            prefs.edit()
                .putString(KEY_ARCHIVED_PUB, decision.archiveStore.publicKeyBase64)
                .putString(KEY_ARCHIVED_PRIV, decision.archiveStore.privateKeyBase64)
                .apply()
        }
        val imported = decision.importIntoMesh
        if (imported != null && mesh == null) {
            val identity = CryptoIdentity.fromBase64(imported.privateKeyBase64, imported.publicKeyBase64)
            if (identity != null) {
                UserPrefs.writeIdentity(context, identity, allowReplace = false)
            }
        }
        val present = decision.present
        if (present != null) {
            prefs.edit()
                .putString(KEY_PUB, present.publicKeyBase64)
                .putString(KEY_PRIV, present.privateKeyBase64)
                .apply()
            return LocalIdentity(
                userId = profile?.userId ?: stableUserIdFromPublicKey(present.publicKeyBase64),
                name = profile?.username ?: "",
                publicKeyBase64 = present.publicKeyBase64,
                privateKeyBase64 = present.privateKeyBase64
            )
        }
        if (UserPrefs.isIdentityHeld(context) || profile == null) {
            throw IllegalStateException("identity generation held")
        }
        val created = UserPrefs.ensureIdentity(context)
        prefs.edit()
            .putString(KEY_PUB, created.publicKeyBase64)
            .putString(KEY_PRIV, created.privateKeyBase64)
            .apply()
        return LocalIdentity(
            userId = profile.userId,
            name = profile.username,
            publicKeyBase64 = created.publicKeyBase64,
            privateKeyBase64 = created.privateKeyBase64
        )
    }

    fun archivedPrivateKey(): String? = prefs.getString(KEY_ARCHIVED_PRIV, null)

    private fun keyMaterial(pubKey: String, privKey: String): QrIdentityAlign.KeyMaterial? {
        val pub = prefs.getString(pubKey, null)
        val priv = prefs.getString(privKey, null)
        if (pub.isNullOrBlank() || priv.isNullOrBlank()) return null
        return QrIdentityAlign.KeyMaterial(pub, priv)
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

    companion object {
        private const val PREFS = "identity_store"
        private const val KEY_PUB = "public_key"
        private const val KEY_PRIV = "private_key"
        private const val KEY_ARCHIVED_PUB = "archived_public_key"
        private const val KEY_ARCHIVED_PRIV = "archived_private_key"
    }
}

data class LocalIdentity(
    val userId: String,
    val name: String,
    val publicKeyBase64: String,
    val privateKeyBase64: String
)

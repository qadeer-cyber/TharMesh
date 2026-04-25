package com.tharmesh.data

import android.content.Context
import com.tharmesh.crypto.CryptoBox
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.ui.theme.ThemeManager
import java.util.UUID

data class UserProfile(
    val userId: String,
    val username: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val authProvider: String = "anonymous"
)

object UserPrefs {

    private const val PREFS_NAME = "tharmesh_user_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_EMAIL = "email"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_PROVIDER = "auth_provider"
    private const val KEY_AUTHENTICATED = "authenticated"
    private const val KEY_PRIVATE_KEY = "identity_private_key_b64"
    private const val KEY_PUBLIC_KEY = "identity_public_key_b64"
    private const val KEY_FINGERPRINT = "identity_fingerprint"

    /**
     * Stage 6.1 — persisted Theme Mode (System / Light / Dark). Stored as the
     * stable enum name string. Default is SYSTEM so a fresh install follows
     * the OS dark-mode setting, which on most devices means the user sees the
     * same dark UI they had before this PR until they explicitly opt in to
     * light mode.
     */
    private const val KEY_THEME_MODE = "theme_mode"

    const val PROVIDER_ANONYMOUS = "anonymous"
    const val PROVIDER_GOOGLE = "google"

    @JvmStatic
    fun hasProfile(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTHENTICATED, false)
    }

    @JvmStatic
    fun ensureProfile(context: Context): UserProfile {
        val existing = readProfile(context)
        if (existing != null) return existing
        val newUserId = "user-" + UUID.randomUUID().toString().take(8)
        return saveProfile(
            context,
            UserProfile(
                userId = newUserId,
                username = newUserId,
                authProvider = PROVIDER_ANONYMOUS
            )
        )
    }

    @JvmStatic
    fun saveProfile(context: Context, profile: UserProfile): UserProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USER_ID, profile.userId)
            .putString(KEY_USERNAME, profile.username)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_AVATAR, profile.avatarUrl)
            .putString(KEY_PROVIDER, profile.authProvider)
            .putBoolean(KEY_AUTHENTICATED, true)
            .apply()
        return profile
    }

    @JvmStatic
    fun readProfile(context: Context): UserProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(KEY_USER_ID, null)
        val username = prefs.getString(KEY_USERNAME, null)
        if (userId.isNullOrBlank() || username.isNullOrBlank()) return null
        return UserProfile(
            userId = userId,
            username = username,
            email = prefs.getString(KEY_EMAIL, null),
            avatarUrl = prefs.getString(KEY_AVATAR, null),
            authProvider = prefs.getString(KEY_PROVIDER, PROVIDER_ANONYMOUS) ?: PROVIDER_ANONYMOUS
        )
    }

    @JvmStatic
    fun signOut(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    /** Stable userId derived from Google's `sub` claim so reinstalls keep the same mesh ID. */
    fun userIdFromGoogleSub(googleSub: String): String {
        val hash = CryptoBox.sha256(googleSub).take(12)
        return "tm-$hash"
    }

    /**
     * Return the device's long-term signing identity, generating + persisting a
     * fresh ECDSA P-256 keypair on first call. Subsequent calls deserialize the
     * stored PKCS#8 / X.509 base64 blobs via [CryptoIdentity.fromBase64].
     *
     * NOTE: the private key is stored in SharedPreferences, NOT Android Keystore.
     * This is a deliberate Stage 4.6 baseline — a later stage should migrate to
     * Keystore (API 23+) for hardware-backed storage. Until then, device
     * compromise == identity compromise. Documented as a Known Limitation.
     */
    @JvmStatic
    @Synchronized
    fun ensureIdentity(context: Context): CryptoIdentity {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val priv = prefs.getString(KEY_PRIVATE_KEY, null)
        val pub = prefs.getString(KEY_PUBLIC_KEY, null)
        if (!priv.isNullOrBlank() && !pub.isNullOrBlank()) {
            val restored = CryptoIdentity.fromBase64(priv, pub)
            if (restored != null) return restored
            // Stored blobs are corrupted — regenerate. Log via println (no Android
            // logger at the UserPrefs layer). This IS a security-relevant event:
            // peers that had pinned our old key via TOFU will now reject us until
            // their state is cleared. Acceptable for the rare corruption case.
            println("UserPrefs.ensureIdentity: stored keypair failed to decode, regenerating")
        }
        val identity = CryptoIdentity.generate()
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, identity.privateKeyBase64)
            .putString(KEY_PUBLIC_KEY, identity.publicKeyBase64)
            .putString(KEY_FINGERPRINT, identity.fingerprint)
            .apply()
        return identity
    }

    /** Read-only — returns null if no identity has been generated yet. */
    fun readIdentity(context: Context): CryptoIdentity? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val priv = prefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val pub = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        return CryptoIdentity.fromBase64(priv, pub)
    }

    /**
     * Stage 6.1 — return the persisted theme mode, defaulting to SYSTEM when
     * the user has never picked a value. Tolerant of missing/garbled values
     * (e.g. a downgrade from a future TharMesh version that adds new modes).
     */
    @JvmStatic
    fun getThemeMode(context: Context): ThemeManager.Mode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeManager.Mode.SYSTEM
        return runCatching { ThemeManager.Mode.valueOf(raw) }.getOrDefault(ThemeManager.Mode.SYSTEM)
    }

    @JvmStatic
    fun setThemeMode(context: Context, mode: ThemeManager.Mode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }
}

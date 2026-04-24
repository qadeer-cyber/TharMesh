package com.tharmesh.data

import android.content.Context
import com.tharmesh.crypto.CryptoBox
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
}

package com.tharmesh.data

import android.content.Context
import java.util.UUID

data class UserProfile(
    val userId: String,
    val username: String
)

object UserPrefs {

    private const val PREFS_NAME = "tharmesh_user_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"

    @JvmStatic
    fun ensureProfile(context: Context): UserProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingUserId = prefs.getString(KEY_USER_ID, null)
        val existingUsername = prefs.getString(KEY_USERNAME, null)

        if (!existingUserId.isNullOrBlank() && !existingUsername.isNullOrBlank()) {
            return UserProfile(existingUserId, existingUsername)
        }

        val newUserId = "user-" + UUID.randomUUID().toString().take(8)
        val newUsername = newUserId
        prefs.edit().putString(KEY_USER_ID, newUserId).putString(KEY_USERNAME, newUsername).apply()
        return UserProfile(newUserId, newUsername)
    }

    @JvmStatic
    fun readProfile(context: Context): UserProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(KEY_USER_ID, null)
        val username = prefs.getString(KEY_USERNAME, null)
        return if (!userId.isNullOrBlank() && !username.isNullOrBlank()) {
            UserProfile(userId, username)
        } else {
            null
        }
    }
}

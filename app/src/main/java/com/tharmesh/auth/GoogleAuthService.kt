package com.tharmesh.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import tharmesh.app.BuildConfig
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile

/**
 * Thin wrapper around [GoogleSignInClient]. Gated behind [BuildConfig.GOOGLE_WEB_CLIENT_ID]:
 * if it's empty (no OAuth credentials configured yet) [isAvailable] returns false and the
 * caller should fall back to the anonymous profile flow.
 */
class GoogleAuthService(context: Context) {

    private val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val isAvailable: Boolean = webClientId.isNotBlank()

    private val client: GoogleSignInClient? = if (isAvailable) {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .build()
        GoogleSignIn.getClient(context.applicationContext, options)
    } else {
        null
    }

    fun silentSignInIntent(): Intent? = client?.signInIntent

    fun lastSignedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /** Map a Google sign-in result to our internal [UserProfile]. */
    fun profileFromIntent(data: Intent?): Result<UserProfile> {
        val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            val account = task.getResult(ApiException::class.java)
                ?: return Result.failure(IllegalStateException("Null GoogleSignInAccount"))
            val sub = account.id ?: return Result.failure(IllegalStateException("Google account missing stable id"))
            Result.success(
                UserProfile(
                    userId = UserPrefs.userIdFromGoogleSub(sub),
                    username = account.displayName?.takeIf { it.isNotBlank() } ?: (account.email ?: "user"),
                    email = account.email,
                    avatarUrl = account.photoUrl?.toString(),
                    authProvider = UserPrefs.PROVIDER_GOOGLE
                )
            )
        } catch (e: ApiException) {
            Result.failure(e)
        }
    }

    fun signOut() {
        client?.signOut()
    }
}

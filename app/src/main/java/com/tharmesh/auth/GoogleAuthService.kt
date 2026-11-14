package com.tharmesh.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import tharmesh.app.BuildConfig

/**
 * Thin wrapper around [GoogleSignInClient]. Gated behind [BuildConfig.GOOGLE_WEB_CLIENT_ID]:
 * if it's empty (no OAuth credentials configured yet) [isAvailable] returns false and the
 * caller can present email authentication.
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

    fun idTokenFromIntent(data: Intent?): Result<String> = runCatching {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        requireNotNull(account?.idToken?.takeIf { it.isNotBlank() })
    }

    fun signOut() {
        client?.signOut()
    }
}

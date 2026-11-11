// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.account

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tharmesh.account.AccountActivation
import com.tharmesh.account.AuthBackend
import com.tharmesh.account.AuthCall
import com.tharmesh.account.AuthRedirect
import com.tharmesh.account.CloudCredential
import com.tharmesh.account.CloudSessionStore
import com.tharmesh.account.InstallRecord
import com.tharmesh.account.LocalAuth
import com.tharmesh.account.PendingSignupStore
import com.tharmesh.account.UrlHttpTransport
import com.tharmesh.data.UserPrefs
import com.tharmesh.ui.main.MainActivity
import com.tharmesh.ui.theme.Appearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.BuildConfig

/**
 * Receives tharmesh://auth/callback. The raw link is not written to logs.
 * A verified session is stored. An unverified or foreign link stays on email verification.
 */
class AuthCallbackActivity : AppCompatActivity() {
    private val http = UrlHttpTransport()

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.applyTheme(this)
        super.onCreate(savedInstanceState)
        val parsed = AuthRedirect.parse(intent?.data?.toString())
        if (parsed !is AuthRedirect.Parsed.Accepted) {
            open(AccountFlowActivity.Screen.VERIFY_EMAIL)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { exchange(parsed) }
            if (result is AuthCall.Ok && result.tokens.emailVerified && result.tokens.accessToken.isNotBlank()) {
                val expires = System.currentTimeMillis() + result.tokens.expiresInSeconds.coerceAtLeast(0L) * 1000L
                CloudSessionStore.write(
                    this@AuthCallbackActivity,
                    InstallRecord(
                        accountId = result.tokens.accountId,
                        accessToken = result.tokens.accessToken,
                        refreshToken = result.tokens.refreshToken.ifBlank { null },
                        emailVerified = true,
                        cloud = CloudCredential.ACTIVE,
                        localAuth = LocalAuth.AWAITING_IDENTITY,
                        holdMeshGeneration = UserPrefs.readIdentity(this@AuthCallbackActivity) == null,
                        tokenExpiresAtMs = expires
                    )
                )
                val next = AccountActivation.afterVerifiedSignIn(
                    hasMeshIdentity = UserPrefs.readIdentity(this@AuthCallbackActivity) != null,
                    pendingSignupOnThisPhone = PendingSignupStore.read(this@AuthCallbackActivity) != null
                )
                PendingSignupStore.clear(this@AuthCallbackActivity)
                when (next) {
                    AccountActivation.AfterVerifiedSignIn.HOME ->
                        startActivity(Intent(this@AuthCallbackActivity, MainActivity::class.java))
                    AccountActivation.AfterVerifiedSignIn.SECURE_IDENTITY ->
                        open(AccountFlowActivity.Screen.SECURE_IDENTITY)
                    AccountActivation.AfterVerifiedSignIn.RECOVER ->
                        open(AccountFlowActivity.Screen.RECOVER)
                }
            } else {
                open(AccountFlowActivity.Screen.VERIFY_EMAIL)
            }
            finish()
        }
    }

    private fun exchange(parsed: AuthRedirect.Parsed.Accepted): AuthCall {
        val base = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        val code = parsed.code
        if (!code.isNullOrBlank()) {
            val verifier = PendingSignupStore.read(this)?.codeVerifier.orEmpty()
            if (verifier.isBlank()) return AuthCall.Failed("callback_rejected")
            return AuthBackend.exchangePkce(http, base, key, code, verifier)
        }
        val hash = parsed.tokenHash
        if (!hash.isNullOrBlank()) {
            return AuthBackend.verifyTokenHash(http, base, key, hash, parsed.type ?: "signup")
        }
        val access = parsed.accessToken
        if (!access.isNullOrBlank()) {
            val user = AuthBackend.fetchUser(http, base, key, access)
            return if (user is AuthCall.Ok && user.tokens.emailVerified) {
                user.copy(tokens = user.tokens.copy(refreshToken = parsed.refreshToken ?: user.tokens.refreshToken))
            } else {
                AuthCall.Failed("callback_rejected")
            }
        }
        return AuthCall.Failed("callback_rejected")
    }

    private fun open(screen: AccountFlowActivity.Screen) {
        startActivity(
            AccountFlowActivity.intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}

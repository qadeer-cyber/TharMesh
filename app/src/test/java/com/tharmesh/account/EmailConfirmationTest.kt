// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNIT tests with a fake HTTP transport. They do not call the live Supabase project
 * and they do not create accounts.
 */
class EmailConfirmationTest {
    private val apiKey = "sb_publishable_test_key"
    private val base = "https://example.supabase.co/"

    @Test
    fun signupWithoutSession_isPending_andSendsAppRedirect() {
        var signupUrl = ""
        var posts = 0
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                posts += 1
                signupUrl = url
                assertEquals(apiKey, headers["apikey"])
                assertFalse(headers.containsKey("Authorization"))
                assertTrue(body.contains("code_challenge"))
                assertFalse(body.contains("sb_publishable_"))
                return HttpTransport.Response(200, "{\"user\":{\"id\":\"acct-9\"}}")
            }
        }
        val pkce = AuthRedirect.generatePkce()
        val result = AuthBackend.signUp(
            http, "  $base  ", "  $apiKey  ", "a@b.co", "password1",
            mapOf("handle" to "sara"), pkce.challenge
        )
        assertTrue(result is AuthCall.PendingVerification)
        assertEquals("acct-9", (result as AuthCall.PendingVerification).accountId)
        assertTrue(signupUrl.contains("/auth/v1/signup?redirect_to="))
        assertTrue(signupUrl.contains("tharmesh"))
        assertFalse(signupUrl.contains("localhost"))
        assertEquals(1, posts)
    }

    @Test
    fun foreignAndMalformedCallbacks_areRejected() {
        assertNull(AuthRedirect.parse("http://localhost:3000"))
        assertNull(AuthRedirect.parse("https://evil.example/auth/callback?code=abc"))
        assertNull(AuthRedirect.parse("tharmesh://other/callback?code=abc"))
        assertNull(AuthRedirect.parse("tharmesh://auth/callback"))
        assertTrue(AuthRedirect.parse("tharmesh://auth/callback?error=access_denied") is AuthRedirect.Parsed.Rejected)
        assertNull(AuthRedirect.parse("tharmesh://auth/callback?access_token=sb_publishable_nope&type=signup"))
        assertNull(AuthRedirect.parse("tharmesh://auth/callback?token_hash=abc&type=not-a-type"))
    }

    @Test
    fun verifiedTokenHashAndPkce_returnASession() {
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                assertFalse(headers.values.any { it.contains("sb_publishable_") && it.startsWith("Bearer") })
                val json = "{\"access_token\":\"user-access\",\"refresh_token\":\"user-refresh\",\"expires_in\":3600," +
                    "\"user\":{\"id\":\"acct-9\",\"email_confirmed_at\":\"2026-03-01T00:00:00Z\"}}"
                return HttpTransport.Response(200, json)
            }
        }
        val verified = AuthRedirect.parse("tharmesh://auth/callback?token_hash=hash-1&type=signup")
        assertTrue(verified is AuthRedirect.Parsed.Accepted)
        val accepted = verified as AuthRedirect.Parsed.Accepted
        val fromHash = AuthBackend.verifyTokenHash(http, base, apiKey, accepted.tokenHash!!, accepted.type!!)
        assertTrue((fromHash as AuthCall.Ok).tokens.emailVerified)
        assertEquals("user-access", fromHash.tokens.accessToken)

        val fromCode = AuthBackend.exchangePkce(http, base, apiKey, "code-1", "verifier-1") as AuthCall.Ok
        assertTrue(fromCode.tokens.emailVerified)
        val missingVerifier = AuthBackend.exchangePkce(http, base, apiKey, "code-1", "")
        assertEquals("callback_rejected", (missingVerifier as AuthCall.Failed).reason)
    }

    @Test
    fun rateLimit_reportsRetryAfter_andDoesNotRetry() {
        var posts = 0
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                posts += 1
                return HttpTransport.Response(
                    429,
                    "{\"error_code\":\"over_email_send_rate_limit\",\"msg\":\"rate limit\"}",
                    42L
                )
            }
        }
        val result = AuthBackend.resendVerification(http, base, apiKey, "a@b.co") as AuthCall.Failed
        assertEquals("rate_limited", result.reason)
        assertEquals(42L, result.retryAfterSeconds)
        assertEquals(1, posts)
    }

    @Test
    fun freshInstall_cannotOpenSecureIdentity_untilVerified() {
        assertFalse(AccountActivation.mayOpenSecureIdentity(false, false, false))
        assertTrue(AccountActivation.mayOpenSecureIdentity(true, false, false))
        assertTrue(AccountActivation.mayOpenSecureIdentity(false, true, false))
        assertTrue(AccountActivation.mayOpenSecureIdentity(false, false, true))
        assertEquals(
            AccountActivation.AfterVerifiedSignIn.SECURE_IDENTITY,
            AccountActivation.afterVerifiedSignIn(hasMeshIdentity = false, pendingSignupOnThisPhone = true)
        )
        assertEquals(
            AccountActivation.AfterVerifiedSignIn.HOME,
            AccountActivation.afterVerifiedSignIn(hasMeshIdentity = true, pendingSignupOnThisPhone = false)
        )
        val source = File("src/main/java/com/tharmesh/ui/account/AccountFlowActivity.kt").let {
            if (it.isFile) it else File("app/src/main/java/com/tharmesh/ui/account/AccountFlowActivity.kt")
        }.readText()
        assertFalse(source.contains("account_action_continue_offline"))
        assertFalse(source.contains("bind(Screen.SECURE_IDENTITY)") && source.contains("tertiary.setOnClickListener { bind(Screen.SECURE_IDENTITY) }"))
    }

    @Test
    fun offlineAccess_requiresAnActivatedOrLegacyInstall() {
        assertEquals(
            OfflineMode.NEEDS_SIGN_IN,
            OfflineModeResolver.resolve(false, false, LocalAuth.AWAITING_IDENTITY, CloudCredential.NONE)
        )
        assertEquals(
            OfflineMode.OFFLINE_AUTHORIZED,
            OfflineModeResolver.resolve(false, true, LocalAuth.OFFLINE_AUTHORIZED, CloudCredential.ACTIVE)
        )
        assertEquals(
            OfflineMode.CLOUD_SIGNED_OUT,
            OfflineModeResolver.resolve(false, true, LocalAuth.OFFLINE_AUTHORIZED, CloudCredential.SIGNED_OUT)
        )
    }

    @Test
    fun pendingSignup_storesNoPassword() {
        val encoded = PendingSignupCodec.encode(PendingSignup("a@b.co", "acct-9", "verifier"))
        assertFalse(encoded.contains("password"))
        val decoded = PendingSignupCodec.decode(encoded)
        assertEquals("a@b.co", decoded?.email)
        assertNull(PendingSignupCodec.decode("{\"email\":\"a@b.co\",\"password\":\"secret\"}"))
    }

    @Test
    fun configured_trimsUrlAndAcceptsPublishableKey() {
        assertTrue(AuthBackend.configured(" https://example.supabase.co/ ", " sb_publishable_test_key "))
        assertFalse(AuthBackend.configured("", "sb_publishable_test_key"))
        assertFalse(AuthBackend.configured("http://example.supabase.co", "sb_publishable_test_key"))
        assertFalse(AuthBackend.configured("https://example.supabase.co", "   "))
    }
}

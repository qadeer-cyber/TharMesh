// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNIT tests with a fake HTTP transport. They do not call the live project
 * and they do not create accounts.
 */
class AuthErrorMappingTest {
    private val apiKey = "sb_publishable_test_key"
    private val base = "https://example.supabase.co"

    @Test
    fun eachErrorCode_isMapped_andDoesNotBecomeHttp400() {
        assertSignIn(
            400,
            "{\"error_code\":\"email_not_confirmed\",\"msg\":\"Email not confirmed\"}",
            "email_unverified",
            "email_not_confirmed"
        )
        assertSignIn(
            400,
            "{\"msg\":\"Email not confirmed\"}",
            "email_unverified",
            "email_not_confirmed"
        )
        assertSignIn(
            400,
            "{\"error\":{\"error_code\":\"otp_expired\",\"msg\":\"Token has expired or is invalid\"}}",
            "otp_expired",
            "otp_expired"
        )
        assertSignIn(
            400,
            "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid login credentials\"}",
            "invalid_credentials",
            "invalid_grant"
        )
        assertSignIn(400, "{\"error_code\":\"weak_password\",\"msg\":\"weak\"}", "weak_password", "weak_password")
        assertSignIn(422, "{\"code\":\"validation_failed\",\"msg\":\"validation\"}", "validation_failed", "validation_failed")
        assertSignIn(422, "{\"error_code\":\"email_exists\",\"msg\":\"already been registered\"}", "account_exists", "email_exists")
        assertSignIn(400, "{\"msg\":\"User already confirmed\"}", "already_confirmed", "already_confirmed")
        assertSignIn(400, "{\"error_code\":\"email_address_invalid\"}", "email_address_invalid", "email_address_invalid")
        assertSignIn(400, "{\"error_code\":\"signup_disabled\"}", "signup_disabled", "signup_disabled")
        assertSignIn(400, "{\"error_code\":\"bad_code_verifier\"}", "pkce_expired", "bad_code_verifier")
        assertSignIn(400, "{\"error_code\":\"flow_state_not_found\"}", "pkce_expired", "flow_state_not_found")
        assertSignIn(400, "{\"error_code\":\"flow_state_expired\"}", "pkce_expired", "flow_state_expired")
    }

    @Test
    fun rateLimit422AndEmpty400_stayDistinct() {
        val limited = post(429, "{\"error_code\":\"over_email_send_rate_limit\",\"msg\":\"rate limit\"}", 30L)
        assertEquals("rate_limited", (limited as AuthCall.Failed).reason)
        assertEquals("over_email_send_rate_limit", limited.diagnostic)
        assertEquals(30L, limited.retryAfterSeconds)

        val empty = post(400, "")
        val failed = empty as AuthCall.Failed
        assertEquals("unrecognized", failed.reason)
        assertEquals("http_400", failed.diagnostic)
        assertFalse(failed.reason == "http_400")
    }

    @Test
    fun actionableFailuresPreserveStatusAndProviderCode() {
        assertSignIn(401, """{"error_code":"invalid_credentials"}""", "invalid_credentials", "invalid_credentials")
        assertSignIn(403, """{"error_code":"email_address_not_authorized"}""", "email_delivery_failed", "email_address_not_authorized")
        assertSignIn(400, """{"error_code":"redirect_to_not_allowed"}""", "redirect_not_allowed", "redirect_to_not_allowed")
        assertSignIn(503, "", "server_error", "http_503")
        val limited = post(429, "", 45L) as AuthCall.Failed
        assertEquals("rate_limited", limited.reason)
        assertEquals(45L, limited.retryAfterSeconds)
    }

    @Test
    fun legacyShortPasswordIsSentToServerForSignIn() {
        val result = AuthBackend.signIn(
            fixed(401, """{"error_code":"invalid_credentials"}"""), base, apiKey, "a@b.co", "short"
        ) as AuthCall.Failed
        assertEquals("invalid_credentials", result.reason)
    }

    @Test
    fun emailOtpRequiresServerVerificationAndPreservesLeadingZero() {
        var calls = 0
        val http = HttpTransport { url, headers, body ->
            calls++
            assertEquals(apiKey, headers["apikey"])
            val payload = org.json.JSONObject(body)
            assertEquals("a@b.co", payload.getString("email"))
            when (calls) {
                1 -> {
                    assertEquals("$base/auth/v1/otp", url)
                    assertTrue(payload.getBoolean("create_user"))
                    HttpTransport.Response(200, "{}")
                }
                else -> {
                    assertEquals("$base/auth/v1/verify", url)
                    assertEquals("email", payload.getString("type"))
                    assertEquals("012345", payload.getString("token"))
                    HttpTransport.Response(403, """{"error_code":"otp_expired"}""")
                }
            }
        }
        val sent = AuthBackend.requestEmailOtp(http, base, apiKey, "a@b.co") as AuthCall.Ok
        assertTrue(sent.tokens.accessToken.isEmpty())
        assertEquals(AuthCall.Failed("otp_invalid"), AuthBackend.verifyEmailOtp(http, base, apiKey, "a@b.co", "12345"))
        val rejected = AuthBackend.verifyEmailOtp(http, base, apiKey, "a@b.co", "012345") as AuthCall.Failed
        assertEquals("otp_expired", rejected.reason)
        assertEquals(2, calls)
    }

    @Test
    fun diagnostic_dropsEmailsAndTokens() {
        val leaked = AuthErrors.classify(
            "{\"msg\":\"nope for person@example.com\",\"error\":\"eyJhbGciOi.secret\"}",
            400
        )
        assertFalse(leaked.diagnostic.contains("@"))
        assertFalse(leaked.diagnostic.contains("eyj"))
        assertFalse(leaked.diagnostic.contains("example.com"))
    }

    @Test
    fun resendOfExistingAccount_doesNotClaimANewMail() {
        val result = AuthBackend.resendVerification(
            fixed(400, "{\"error_code\":\"email_exists\",\"msg\":\"already been registered\"}"),
            base,
            apiKey,
            "a@b.co"
        ) as AuthCall.Failed
        assertEquals("account_exists", result.reason)
        assertEquals("email_exists", result.diagnostic)
    }

    @Test
    fun missingPkceVerifier_asksForPasswordSignIn() {
        val result = AuthBackend.exchangePkce(fixed(200, "{}"), base, apiKey, "code-1", "")
        assertEquals("pkce_verifier_missing", (result as AuthCall.Failed).reason)
        val parsed = AuthRedirect.parse(
            "tharmesh://auth/callback?error=access_denied&error_code=otp_expired&error_description=expired"
        )
        assertTrue(parsed is AuthRedirect.Parsed.Rejected)
        assertEquals("otp_expired", (parsed as AuthRedirect.Parsed.Rejected).reason)
    }

    @Test
    fun signupStillSendsRedirectOnTheQueryAndInTheBody() {
        var url = ""
        var body = ""
        val http = object : HttpTransport {
            override fun post(target: String, headers: Map<String, String>, payload: String): HttpTransport.Response {
                url = target
                body = payload
                return HttpTransport.Response(200, "{\"user\":{\"id\":\"acct-1\"}}")
            }
        }
        val result = AuthBackend.signUp(http, base, apiKey, "a@b.co", "password1")
        assertTrue(result is AuthCall.PendingVerification)
        assertTrue(url.contains("redirect_to="))
        assertTrue(url.contains("tharmesh"))
        assertFalse(url.contains("localhost"))
        assertTrue(body.contains("\"redirect_to\":\"tharmesh://auth/callback\""))
    }

    private fun assertSignIn(code: Int, body: String, reason: String, diagnostic: String) {
        val result = AuthBackend.signIn(fixed(code, body), base, apiKey, "a@b.co", "password1") as AuthCall.Failed
        assertEquals(reason, result.reason)
        assertEquals(diagnostic, result.diagnostic)
        assertFalse(result.reason.startsWith("http_"))
    }

    private fun post(code: Int, body: String, retry: Long? = null): AuthCall {
        return AuthBackend.signIn(fixed(code, body, retry), base, apiKey, "a@b.co", "password1")
    }

    private fun fixed(code: Int, body: String, retry: Long? = null): HttpTransport {
        return object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, payload: String): HttpTransport.Response {
                return HttpTransport.Response(code, body, retry)
            }
        }
    }
}

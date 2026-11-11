// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCoreTest {

    @Test
    fun recoveryRoundTrip_andWrongPhraseFails() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3)) }
        val phrase = IdentityRecovery.generatePhrase(random)
        val sealed = IdentityRecovery.seal("PRIVATE-KEY-BLOB", phrase, iterations = 1_000, random = random)
        assertEquals("PRIVATE-KEY-BLOB", IdentityRecovery.open(sealed, phrase))
        assertEquals("PRIVATE-KEY-BLOB", IdentityRecovery.open(sealed, "  $phrase  "))
        assertNull(IdentityRecovery.open(sealed, "wrong phrase here now"))
        assertNotEquals(phrase, sealed.ciphertextB64)
    }

    @Test
    fun expiredAccessToken_doesNotDisableLocalActivation() {
        val activation = AccountSession.Activation(
            accountId = "acct-1",
            username = "qadeer",
            identityFingerprint = "abcdabcdabcdabcd",
            activatedAtMs = 10L
        )
        assertTrue(AccountSession.offlineAuthorized(activation, accessTokenExpiresAtMs = 50L))
    }

    @Test
    fun signIn_refusesToReplaceAnExistingIdentity() {
        assertEquals(
            AccountSession.Bind.KEEP_LOCAL_IDENTITY,
            AccountSession.bind("local-fp", "local-fp")
        )
        assertEquals(
            AccountSession.Bind.KEEP_LOCAL_IDENTITY,
            AccountSession.bind("local-fp", null)
        )
        assertEquals(
            AccountSession.Bind.REFUSE_SILENT_REPLACEMENT,
            AccountSession.bind("local-fp", "other-fp")
        )
        assertEquals(
            AccountSession.Bind.NEEDS_RECOVERY_OR_NEW_IDENTITY,
            AccountSession.bind(null, "server-fp")
        )
    }

    @Test
    fun unconfiguredBackend_doesNotInventASession() {
        val http = HttpTransport { _, _, _ ->
            throw AssertionError("network must not be called when config is empty")
        }
        val result = AuthBackend.signIn(http, "", "", "a@b.co", "password1")
        assertTrue(result is AuthCall.Failed)
        assertEquals("cloud_auth_not_configured", (result as AuthCall.Failed).reason)
    }

    @Test
    fun signIn_parsesGoTrueToken_andRejectsHttpFailure() {
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                assertTrue(url.contains("/auth/v1/token?grant_type=password"))
                assertEquals("anon-key", headers["apikey"])
                return if (body.contains("bad@b.co")) {
                    HttpTransport.Response(400, "{\"error\":\"invalid\"}")
                } else {
                    HttpTransport.Response(
                        200,
                        "{\"access_token\":\"tok\",\"expires_in\":3600,\"user\":{\"id\":\"user-uuid\"}}"
                    )
                }
            }
        }
        val ok = AuthBackend.signIn(http, "https://example.supabase.co", "anon-key", "a@b.co", "password1")
        assertTrue(ok is AuthCall.Ok)
        assertEquals("user-uuid", (ok as AuthCall.Ok).tokens.accountId)
        val bad = AuthBackend.signIn(http, "https://example.supabase.co", "anon-key", "bad@b.co", "password1")
        assertTrue(bad is AuthCall.Failed)
    }
}

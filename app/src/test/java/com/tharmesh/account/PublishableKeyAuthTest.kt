// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNIT tests with a fake HTTP transport. They do not call a live Supabase project.
 */
class PublishableKeyAuthTest {

    private val apiKey = "sb_publishable_test_key"
    private val base = "https://example.supabase.co"

    @Test
    fun signup_signIn_andVerification_sendApikeyWithoutPublishableBearer() {
        val seen = ArrayList<Map<String, String>>()
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                seen += headers
                return when {
                    url.endsWith("/auth/v1/signup") ->
                        HttpTransport.Response(200, "{\"user\":{\"id\":\"acct-1\",\"email_confirmed_at\":null}}")
                    body.contains("pending@b.co") ->
                        HttpTransport.Response(400, "{\"error\":\"email_not_confirmed\",\"error_description\":\"Email not confirmed\"}")
                    url.contains("grant_type=password") ->
                        HttpTransport.Response(
                            200,
                            "{\"access_token\":\"user-access\",\"refresh_token\":\"user-refresh\",\"expires_in\":3600," +
                                "\"user\":{\"id\":\"acct-1\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"}}"
                        )
                    else -> HttpTransport.Response(200, "{}")
                }
            }
        }
        val signedUp = AuthBackend.signUp(http, base, apiKey, "a@b.co", "password1", mapOf("handle" to "sara"))
        assertTrue(signedUp is AuthCall.PendingVerification)
        assertEquals("acct-1", (signedUp as AuthCall.PendingVerification).accountId)

        val unconfirmed = AuthBackend.signIn(http, base, apiKey, "pending@b.co", "password1")
        assertEquals("email_unverified", (unconfirmed as AuthCall.Failed).reason)

        val signedIn = AuthBackend.signIn(http, base, apiKey, "a@b.co", "password1") as AuthCall.Ok
        assertEquals("acct-1", signedIn.tokens.accountId)
        assertTrue(signedIn.tokens.emailVerified)
        assertEquals("user-refresh", signedIn.tokens.refreshToken)

        val resent = AuthBackend.resendVerification(http, base, apiKey, "a@b.co")
        assertTrue(resent is AuthCall.Ok)

        for (headers in seen) {
            assertEquals(apiKey, headers["apikey"])
            assertFalse(headers.containsKey("Authorization"))
            assertPublishableKeyNotInAuthorization(headers)
        }
    }

    @Test
    fun authenticatedProfile_usesUserBearer_directoryWithoutSessionDoesNot() {
        val seen = ArrayList<Pair<String, Map<String, String>>>()
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                seen += url to headers
                return HttpTransport.Response(201, "{}")
            }
            override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
                seen += url to headers
                return HttpTransport.Response(
                    200,
                    "[{\"account_id\":\"acct-2\",\"handle\":\"sara\",\"display_name\":\"Sara\"}]"
                )
            }
        }
        val profile = SupabaseDirectory.upsertProfile(
            http, base, apiKey, "user-access-token", "acct-1", "sara", "Sara", null
        )
        assertTrue(profile is AuthCall.Ok)
        val openDirectory = DirectoryLookup.search(http, base, apiKey, "", "sara") as DirectoryLookup.Result.Hits
        assertEquals("acct-2", openDirectory.people.single().accountId)
        val authedDirectory = DirectoryLookup.search(http, base, apiKey, "user-access-token", "sara")
        assertTrue(authedDirectory is DirectoryLookup.Result.Hits)

        val profileHeaders = seen.first { it.first.contains("/rest/v1/handles") }.second
        assertEquals(apiKey, profileHeaders["apikey"])
        assertEquals("Bearer user-access-token", profileHeaders["Authorization"])
        assertPublishableKeyNotInAuthorization(profileHeaders)

        val openHeaders = seen.first { it.first.contains("/rest/v1/profiles") && !it.second.containsKey("Authorization") }.second
        assertEquals(apiKey, openHeaders["apikey"])
        assertFalse(openHeaders.containsKey("Authorization"))

        val authedHeaders = seen.last { it.first.contains("/rest/v1/profiles") }.second
        assertEquals("Bearer user-access-token", authedHeaders["Authorization"])
        assertPublishableKeyNotInAuthorization(authedHeaders)
    }

    @Test
    fun expiredAccessToken_refreshesOrSignsOutCloudWithoutTouchingMeshKey() {
        val mesh = MeshMaterial.from(CryptoIdentity.generate(), "user-legacy")
        val record = InstallRecord(
            mesh = mesh,
            accountId = "acct-1",
            localAuth = LocalAuth.OFFLINE_AUTHORIZED,
            cloud = CloudCredential.ACTIVE,
            accessToken = "expired-jwt",
            refreshToken = "refresh-1"
        )
        val refreshed = AuthBackend.refresh(
            object : HttpTransport {
                override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                    assertTrue(url.contains("grant_type=refresh_token"))
                    assertEquals(apiKey, headers["apikey"])
                    assertFalse(headers.containsKey("Authorization"))
                    assertPublishableKeyNotInAuthorization(headers)
                    assertTrue(body.contains("refresh-1"))
                    return HttpTransport.Response(
                        200,
                        "{\"access_token\":\"fresh-access\",\"refresh_token\":\"refresh-2\",\"expires_in\":3600," +
                            "\"user\":{\"id\":\"acct-1\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"}}"
                    )
                }
            },
            base,
            apiKey,
            record.refreshToken!!
        )
        val kept = CloudAuthSession.onUnauthorized(record, refreshed)
        assertEquals("fresh-access", kept.accessToken)
        assertEquals("refresh-2", kept.refreshToken)
        assertEquals(mesh.privateKeyBase64, kept.mesh!!.privateKeyBase64)
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, kept.localAuth)

        val rejected = AuthBackend.refresh(
            object : HttpTransport {
                override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                    assertPublishableKeyNotInAuthorization(headers)
                    return HttpTransport.Response(401, "{\"error\":\"invalid_grant\"}")
                }
            },
            base,
            apiKey,
            "refresh-1"
        )
        assertEquals("unauthorized", (rejected as AuthCall.Failed).reason)
        val signedOut = CloudAuthSession.onUnauthorized(record, rejected)
        assertNull(signedOut.accessToken)
        assertNull(signedOut.refreshToken)
        assertEquals(CloudCredential.SIGNED_OUT, signedOut.cloud)
        assertEquals(mesh.privateKeyBase64, signedOut.mesh!!.privateKeyBase64)
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, signedOut.localAuth)
    }

    @Test
    fun publishableKey_isNeverCopiedIntoAuthorization() {
        val headers = listOf(
            SupabaseHeaders.build(apiKey),
            SupabaseHeaders.build(apiKey, apiKey),
            SupabaseHeaders.build(apiKey, "sb_publishable_other"),
            SupabaseHeaders.build(apiKey, "user-access")
        )
        assertFalse(headers[0].containsKey("Authorization"))
        assertFalse(headers[1].containsKey("Authorization"))
        assertFalse(headers[2].containsKey("Authorization"))
        assertEquals("Bearer user-access", headers[3]["Authorization"])
        headers.forEach { assertPublishableKeyNotInAuthorization(it) }
    }

    private fun assertPublishableKeyNotInAuthorization(headers: Map<String, String>) {
        val authorization = headers["Authorization"].orEmpty()
        assertFalse(authorization.contains("sb_publishable_"))
        assertFalse(authorization.contains(apiKey))
    }
}

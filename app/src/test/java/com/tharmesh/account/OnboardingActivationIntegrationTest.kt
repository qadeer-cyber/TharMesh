// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Category 2: mock-backed integration through real provisioning/auth paths.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingActivationIntegrationTest {

    private val account = "e1000000-0000-4000-8000-000000000001"
    private val base = "https://example.supabase.co"
    private val apiKey = "publishable-key"

  private fun tokens() = AuthTokens(account, "access-token", 3600, emailVerified = true)

    @Test fun googleCancel_preservesMeshAndUi() {
        val mesh = CryptoIdentity.generate()
        val parsed = AuthRedirect.parse(
            "tharmesh://auth/callback?error=access_denied&error_description=user_cancelled"
        )
        assertTrue(parsed is AuthRedirect.Parsed.Rejected)
        val ui = OnboardingFlowDecisions.afterGoogleCancel()
        assertEquals(OnboardingFlowDecisions.Screen.GOOGLE, ui.screen)
        assertTrue(ui.meshPreserved)
        assertEquals(mesh.publicKeyBase64, mesh.publicKeyBase64)
    }

    @Test fun wrongOtp_preservesMeshAndStaysOnOtpScreen() {
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String) =
                HttpTransport.Response(400, """{"error_code":"invalid_token"}""")
        }
        val call = AuthBackend.verifyEmailOtp(http, base, apiKey, "a@test.co", "000000")
        assertTrue(call is AuthCall.Failed)
        val failed = call as AuthCall.Failed
        val ui = OnboardingFlowDecisions.afterOtpFailure(failed.reason)
        assertEquals(OnboardingFlowDecisions.Screen.EMAIL_OTP, ui.screen)
        assertEquals(failed.reason, ui.statusReason)
        assertTrue(ui.meshPreserved)
    }

    @Test fun expiredOtp_mapsAndPreservesMesh() {
        val http = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: String) =
                HttpTransport.Response(403, """{"error_code":"otp_expired"}""")
        }
        val call = AuthBackend.verifyEmailOtp(http, base, apiKey, "a@test.co", "123456")
        val ui = OnboardingFlowDecisions.afterOtpFailure((call as AuthCall.Failed).reason)
        assertEquals("otp_expired", ui.statusReason)
        assertTrue(ui.meshPreserved)
    }

    @Test fun networkLostDuringActivation_retriesWithoutNewKey() {
        val mesh = CryptoIdentity.generate()
        var creates = 0
        val client = IdentityProvisioning(
            HttpTransport { _, _, _ -> throw java.io.IOException("network lost") },
            base, apiKey, tokens()
        )
        val stage: (Boolean) -> CryptoIdentity? = {
            if (it) creates++
            mesh
        }
        assertSame(IdentityProvisioning.Result.Retry, client.activate(stage))
        assertEquals(0, creates)
        val ui = OnboardingFlowDecisions.afterProvisioning(IdentityProvisioning.Result.Retry, mesh, mesh)
        assertTrue(ui.meshPreserved)
    }

    @Test fun edgeUnavailable_returnsRetry() {
        val mesh = CryptoIdentity.generate()
        val client = IdentityProvisioning(
            HttpTransport { url, _, _ ->
                if (url.contains("identity_state")) state("unprovisioned")
                else HttpTransport.Response(503, "{}")
            },
            base, apiKey, tokens()
        )
        val out = client.activate { if (it) mesh else null }
        assertSame(IdentityProvisioning.Result.Retry, out)
        assertTrue(OnboardingFlowDecisions.afterProvisioning(out, mesh, mesh).meshPreserved)
    }

    @Test fun lostResponseAfterCommit_reusesStagedKey() {
        val mesh = CryptoIdentity.generate()
        var finishAttempts = 0
        val nonce = "e1000000-0000-4000-8000-000000000002"
        val client = IdentityProvisioning(
            HttpTransport { url, _, body ->
                when {
                    url.contains("identity_state") ->
                        if (finishAttempts > 0) state("active", mesh.publicKeyBase64)
                        else state("unprovisioned")
                    JSONObject(body).optString("operation") == "challenge" ->
                        HttpTransport.Response(200, JSONObject().put("nonce", nonce).toString())
                    else -> {
                        finishAttempts++
                        if (finishAttempts == 1) throw java.io.IOException("lost response")
                        HttpTransport.Response(200, JSONArray().put(
                            JSONObject().put("state", "active").put("mesh_public_key", mesh.publicKeyBase64).put("generation", 1)
                        ).toString())
                    }
                }
            },
            base, apiKey, tokens()
        )
        val stage: (Boolean) -> CryptoIdentity? = { mesh }
        assertSame(IdentityProvisioning.Result.Retry, client.activate(stage))
        val activated = client.activate(stage) as IdentityProvisioning.Result.Activated
        assertEquals(mesh.publicKeyBase64, activated.identity.publicKeyBase64)
    }

    @Test fun killedDuringStaging_readsSameKeyFromBox() {
        val mesh = CryptoIdentity.generate()
        val ui = OnboardingFlowDecisions.afterProvisioning(IdentityProvisioning.Result.Retry, null, mesh)
        assertTrue(ui.meshPreserved)
        assertEquals(OnboardingFlowDecisions.Screen.SECURE_IDENTITY, ui.screen)
    }

    @Test fun restartAfterActivation_alignsActive() {
        val mesh = CryptoIdentity.generate()
        val client = IdentityProvisioning(
            HttpTransport { url, _, _ ->
                if (url.contains("identity_state")) state("active", mesh.publicKeyBase64)
                else HttpTransport.Response(500, "{}")
            },
            base, apiKey, tokens()
        )
        val out = client.activate { if (it) mesh else mesh } as IdentityProvisioning.Result.Activated
        assertEquals(mesh.publicKeyBase64, out.identity.publicKeyBase64)
    }

    @Test fun signOut_clearsSessionPath() {
        val mesh = CryptoIdentity.generate()
        val ui = OnboardingFlowDecisions.afterSignOut(hadMesh = true)
        assertEquals(OnboardingFlowDecisions.Screen.SIGN_IN, ui.screen)
        assertTrue(ui.meshPreserved)
        assertNotEquals("", mesh.fingerprint)
    }

    @Test fun accountChange_doesNotSilentlyReplaceMesh() {
        val mesh = CryptoIdentity.generate()
        val ui = OnboardingFlowDecisions.afterAccountChange(mesh, newAccount = true)
        assertTrue(ui.meshPreserved)
    }

    @Test fun identityConflictWithAnotherAccount_failsClosed() {
        val mesh = CryptoIdentity.generate()
        val decision = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                "active",
                CryptoIdentity.generate().publicKeyBase64,
                mesh,
                false,
                1,
                true
            )
        )
        val ui = OnboardingFlowDecisions.offlineReturningUser(decision)
        assertEquals(LegacyReconciliation.Decision.CONFLICT, decision)
        assertEquals(OnboardingFlowDecisions.Screen.LEGACY_RECONCILE, ui.screen)
    }

    @Test fun offlineReturningUser_keepsLocalMeshWhenAllowed() {
        val mesh = CryptoIdentity.generate()
        val decision = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence("legacy_unknown", null, mesh, false, 0, false)
        )
        val ui = OnboardingFlowDecisions.offlineReturningUser(decision)
        assertEquals(OnboardingFlowDecisions.Screen.OFFLINE, ui.screen)
        assertTrue(ui.meshPreserved)
    }

    private fun state(name: String, key: String? = null): HttpTransport.Response {
        val row = JSONObject().put("state", name)
        if (key != null) row.put("mesh_public_key", key).put("generation", 1)
        return HttpTransport.Response(200, JSONArray().put(row).toString())
    }
}

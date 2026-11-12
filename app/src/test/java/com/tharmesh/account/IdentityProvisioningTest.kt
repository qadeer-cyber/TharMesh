package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import com.tharmesh.identity.CryptoIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IdentityProvisioningTest {
    private val account = "e1000000-0000-4000-8000-000000000001"
    private val nonce = "e1000000-0000-4000-8000-000000000002"

    @Test fun lostResponseReusesOnlyTheCommittedKey() {
        val identity = CryptoIdentity.generate()
        var committed = false
        var creates = 0
        val client = client(HttpTransport { url, headers, body ->
            assertEquals("Bearer user-token", headers["Authorization"])
            val request = JSONObject(body)
            when {
                url.endsWith("tharmesh_identity_state") ->
                    if (committed) state("active", identity.publicKeyBase64) else state("unprovisioned")
                request.getString("operation") == "challenge" ->
                    HttpTransport.Response(200, JSONObject().put("nonce", nonce).toString())
                else -> {
                    assertEquals(identity.publicKeyBase64, request.getString("key"))
                    assertEquals(nonce, request.getString("nonce"))
                    assertEquals(64, Base64Url.decode(request.getString("signature"))!!.size)
                    committed = true
                    throw java.io.IOException("Lost response")
                }
            }
        })
        val stage: (Boolean) -> CryptoIdentity? = { allowCreate ->
            if (allowCreate) creates++
            identity
        }
        assertSame(IdentityProvisioning.Result.Retry, client.activate(stage))
        assertEquals(identity, (client.activate(stage) as IdentityProvisioning.Result.Activated).identity)
        assertEquals(1, creates)
        assertEquals(IdentityProvisioning.Result.RecoveryRequired(identity.publicKeyBase64), client.activate { null })
    }

    @Test fun historicalAccountAndMissingMigrationNeverGenerateKeys() {
        val noStage: (Boolean) -> CryptoIdentity? = { error("Must not stage a key") }
        assertEquals(
            IdentityProvisioning.Result.RecoveryRequired(),
            client(HttpTransport { _, _, _ -> state("legacy_unknown") }).activate(noStage)
        )
        assertSame(
            IdentityProvisioning.Result.Retry,
            client(HttpTransport { _, _, _ -> HttpTransport.Response(404, "{}") }).activate(noStage)
        )
    }

    private fun client(transport: HttpTransport) = IdentityProvisioning(
        transport, "https://supabase.invalid", "public-key", AuthTokens(account, "user-token", 60, true)
    )

    private fun state(name: String, key: String? = null): HttpTransport.Response {
        val row = JSONObject().put("state", name)
        if (key != null) row.put("mesh_public_key", key).put("generation", 1)
        return HttpTransport.Response(200, JSONArray().put(row).toString())
    }
}

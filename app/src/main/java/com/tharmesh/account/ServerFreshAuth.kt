// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONObject

/** Server-enforced fresh auth; SharedPreferences timestamps are not trusted. */
object ServerFreshAuth {
    private const val MAX_AGE_SEC = 900

    data class ActionGrant(val call: AuthCall, val grantId: String?)

    fun recordSession(http: HttpTransport, baseUrl: String, anonKey: String, accessToken: String): AuthCall {
        return recordActionGrant(http, baseUrl, anonKey, accessToken, "sensitive").call
    }

    fun recordActionGrant(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        action: String
    ): ActionGrant {
        if (!AuthBackend.configured(baseUrl, anonKey)) {
            return ActionGrant(AuthCall.Failed("cloud_auth_not_configured"), null)
        }
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return ActionGrant(AuthCall.Failed("unauthorized"), null)
        val body = JSONObject().put("operation", "record").put("action", action)
        val response = postEdgeRaw(http, baseUrl, headers, "sensitive-auth", body)
        if (response == null) return ActionGrant(AuthCall.Failed("network"), null)
        if (response.code !in 200..299) return ActionGrant(AuthCall.Failed("http_${response.code}"), null)
        val grant = try {
            org.json.JSONObject(response.body).optString("grant_id").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        return ActionGrant(AuthCall.Ok(AuthTokens("", "", 0L)), grant)
    }

    fun isFreshOnServer(http: HttpTransport, baseUrl: String, anonKey: String, accessToken: String): Boolean {
        if (!AuthBackend.configured(baseUrl, anonKey)) return false
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return false
        val rpc = try {
            http.post(
                baseUrl.trimEnd('/') + "/rest/v1/rpc/tharmesh_auth_fresh_required",
                headers,
                JSONObject().put("p_max_age_seconds", MAX_AGE_SEC).toString()
            )
        } catch (_: Throwable) {
            return false
        }
        if (rpc.code !in 200..299) return false
        return rpc.body.trim() == "true"
    }

    private fun postEdge(
        http: HttpTransport,
        baseUrl: String,
        headers: Map<String, String>,
        functionName: String,
        body: JSONObject
    ): AuthCall {
        val response = postEdgeRaw(http, baseUrl, headers, functionName, body)
            ?: return AuthCall.Failed("network")
        return if (response.code in 200..299) AuthCall.Ok(AuthTokens("", "", 0L))
        else AuthCall.Failed("http_${response.code}")
    }

    private fun postEdgeRaw(
        http: HttpTransport,
        baseUrl: String,
        headers: Map<String, String>,
        functionName: String,
        body: JSONObject
    ): HttpTransport.Response? {
        return try {
            http.post(
                baseUrl.trimEnd('/') + "/functions/v1/$functionName",
                headers,
                body.toString()
            )
        } catch (_: Throwable) {
            null
        }
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONObject

/** Server-enforced fresh auth; SharedPreferences timestamps are not trusted. */
object ServerFreshAuth {
    private const val MAX_AGE_SEC = 900

    fun recordSession(http: HttpTransport, baseUrl: String, anonKey: String, accessToken: String): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        return postEdge(http, baseUrl, headers, "sensitive-auth", JSONObject().put("operation", "record"))
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
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/functions/v1/$functionName",
                headers,
                body.toString()
            )
        } catch (_: Throwable) {
            return AuthCall.Failed("network")
        }
        return if (response.code in 200..299) AuthCall.Ok(AuthTokens("", "", 0L))
        else AuthCall.Failed("http_${response.code}")
    }
}

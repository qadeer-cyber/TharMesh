// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import org.json.JSONObject

/**
 * Cloud account directory. Mesh transport does not call this.
 * Supabase GoTrue is the selected HTTP API because this tree is Gradle 6.5 /
 * AGP 4.1.3 and cannot take a current Firebase Gradle plugin.
 * URL and anon key come from the build config. Empty values fail closed.
 */
fun interface HttpTransport {
    data class Response(val code: Int, val body: String)
    fun post(url: String, headers: Map<String, String>, body: String): Response
}

data class AuthTokens(
    val accountId: String,
    val accessToken: String,
    val expiresInSeconds: Long
)

sealed class AuthCall {
    data class Ok(val tokens: AuthTokens) : AuthCall()
    data class Failed(val reason: String) : AuthCall()
}

object AuthBackend {

    fun signUp(http: HttpTransport, baseUrl: String, anonKey: String, email: String, password: String): AuthCall {
        return call(http, baseUrl, anonKey, email, password, path = "/auth/v1/signup", query = "")
    }

    fun signIn(http: HttpTransport, baseUrl: String, anonKey: String, email: String, password: String): AuthCall {
        return call(
            http, baseUrl, anonKey, email, password,
            path = "/auth/v1/token",
            query = "?grant_type=password"
        )
    }

    private fun call(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        email: String,
        password: String,
        path: String,
        query: String
    ): AuthCall {
        if (baseUrl.isBlank() || anonKey.isBlank()) {
            return AuthCall.Failed("cloud_auth_not_configured")
        }
        if (!email.contains("@") || password.length < 8) {
            return AuthCall.Failed("invalid_credentials_shape")
        }
        val url = baseUrl.trimEnd('/') + path + query
        val payload = JSONObject().put("email", email.trim()).put("password", password).toString()
        val response = try {
            http.post(
                url,
                mapOf(
                    "apikey" to anonKey,
                    "Authorization" to "Bearer $anonKey",
                    "Content-Type" to "application/json"
                ),
                payload
            )
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        if (response.code !in 200..299) return AuthCall.Failed("http_${response.code}")
        return try {
            val json = JSONObject(response.body)
            val user = if (json.has("user")) json.getJSONObject("user") else json
            val id = user.optString("id", "")
            val token = json.optString("access_token", "")
            val expires = json.optLong("expires_in", 0L)
            if (id.isBlank()) return AuthCall.Failed("missing_account_id")
            AuthCall.Ok(AuthTokens(id, token, expires))
        } catch (ignored: Throwable) {
            AuthCall.Failed("bad_response")
        }
    }
}

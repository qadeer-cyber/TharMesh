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

    /** GoTrue's user lookup is a GET. Default keeps older post-only fakes compiling. */
    fun get(url: String, headers: Map<String, String>): Response = Response(501, "")
}

data class AuthTokens(
    val accountId: String,
    val accessToken: String,
    val expiresInSeconds: Long,
    val emailVerified: Boolean = false,
    val refreshToken: String = ""
)

sealed class AuthCall {
    data class Ok(val tokens: AuthTokens) : AuthCall()
    data class PendingVerification(val accountId: String) : AuthCall()
    data class Failed(val reason: String) : AuthCall()
}

object AuthBackend {

    fun signUp(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        email: String,
        password: String,
        metadata: Map<String, String> = emptyMap()
    ): AuthCall {
        return call(
            http, baseUrl, anonKey, email, password,
            path = "/auth/v1/signup",
            query = "",
            metadata = metadata
        )
    }

    fun signIn(http: HttpTransport, baseUrl: String, anonKey: String, email: String, password: String): AuthCall {
        return call(
            http, baseUrl, anonKey, email, password,
            path = "/auth/v1/token",
            query = "?grant_type=password"
        )
    }

    /** Google ID token from the existing Sign-In client. Not a local profile. */
    fun signInWithGoogleIdToken(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        idToken: String
    ): AuthCall {
        if (!configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (idToken.isBlank()) return AuthCall.Failed("invalid_credentials_shape")
        val url = baseUrl.trimEnd('/') + "/auth/v1/token?grant_type=id_token"
        val body = JSONObject().put("provider", "google").put("id_token", idToken).toString()
        return postAuth(http, url, anonKey, body, allowEmptyToken = false)
    }

    fun resendVerification(http: HttpTransport, baseUrl: String, anonKey: String, email: String): AuthCall {
        if (!configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val url = baseUrl.trimEnd('/') + "/auth/v1/resend"
        val body = JSONObject().put("type", "signup").put("email", email.trim()).toString()
        val response = try {
            http.post(url, authHeaders(anonKey), body)
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        return if (response.code in 200..299) {
            AuthCall.Ok(AuthTokens("", "", 0L))
        } else {
            AuthCall.Failed("http_${response.code}")
        }
    }

    fun fetchUser(http: HttpTransport, baseUrl: String, anonKey: String, accessToken: String): AuthCall {
        if (!configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (accessToken.isBlank()) return AuthCall.Failed("missing_account_id")
        val url = baseUrl.trimEnd('/') + "/auth/v1/user"
        val response = try {
            http.get(url, authHeaders(anonKey, accessToken))
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        if (response.code !in 200..299) return AuthCall.Failed("http_${response.code}")
        return parseTokens(response.body, allowEmptyToken = true)
    }

    fun configured(baseUrl: String, anonKey: String): Boolean {
        return baseUrl.isNotBlank() && anonKey.isNotBlank() && baseUrl.trim().startsWith("https://")
    }

    private fun call(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        email: String,
        password: String,
        path: String,
        query: String,
        metadata: Map<String, String> = emptyMap()
    ): AuthCall {
        if (!configured(baseUrl, anonKey)) {
            return AuthCall.Failed("cloud_auth_not_configured")
        }
        if (!email.contains("@") || password.length < 8) {
            return AuthCall.Failed("invalid_credentials_shape")
        }
        val url = baseUrl.trimEnd('/') + path + query
        val payload = JSONObject().put("email", email.trim()).put("password", password)
        if (metadata.isNotEmpty()) {
            val data = JSONObject()
            for ((k, v) in metadata) data.put(k, v)
            payload.put("data", data)
        }
        return postAuth(http, url, anonKey, payload.toString(), allowEmptyToken = path.endsWith("/signup"))
    }

    private fun postAuth(
        http: HttpTransport,
        url: String,
        anonKey: String,
        body: String,
        allowEmptyToken: Boolean
    ): AuthCall {
        val response = try {
            http.post(url, authHeaders(anonKey), body)
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        if (response.code !in 200..299) return AuthCall.Failed("http_${response.code}")
        return parseTokens(response.body, allowEmptyToken)
    }

    private fun authHeaders(anonKey: String, bearer: String = anonKey): Map<String, String> {
        return mapOf(
            "apikey" to anonKey,
            "Authorization" to "Bearer $bearer",
            "Content-Type" to "application/json"
        )
    }

    private fun parseTokens(body: String, allowEmptyToken: Boolean): AuthCall {
        return try {
            val json = JSONObject(body)
            val user = if (json.has("user") && json.opt("user") is JSONObject) {
                json.getJSONObject("user")
            } else {
                json
            }
            val id = user.optString("id", "")
            val token = json.optString("access_token", "")
            val expires = json.optLong("expires_in", 0L)
            val refresh = json.optString("refresh_token", "")
            val confirmed = user.optString("email_confirmed_at", "")
            val verified = confirmed.isNotBlank() && confirmed != "null"
            if (id.isBlank() && token.isBlank()) return AuthCall.Failed("missing_account_id")
            if (token.isBlank()) {
                return if (allowEmptyToken && id.isNotBlank()) {
                    AuthCall.PendingVerification(id)
                } else {
                    AuthCall.Failed("missing_account_id")
                }
            }
            if (id.isBlank()) return AuthCall.Failed("missing_account_id")
            AuthCall.Ok(AuthTokens(id, token, expires, verified, refresh))
        } catch (ignored: Throwable) {
            AuthCall.Failed("bad_response")
        }
    }
}

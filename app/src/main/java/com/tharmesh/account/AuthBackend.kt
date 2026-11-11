// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Cloud account directory. Mesh transport does not call this.
 * Supabase GoTrue is the selected HTTP API because this tree is Gradle 6.5 /
 * AGP 4.1.3 and cannot take a current Firebase Gradle plugin.
 * URL and anon key come from the build config. Empty values fail closed.
 */
fun interface HttpTransport {
    data class Response(val code: Int, val body: String, val retryAfterSeconds: Long? = null)
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
    data class Failed(
        val reason: String,
        val retryAfterSeconds: Long? = null,
        val diagnostic: String = ""
    ) : AuthCall()
}

/**
 * Header rules for the Supabase publishable key (`sb_publishable_...`).
 * The key is not a JWT and is never placed in `Authorization`.
 * A bearer token is attached only for a distinct user access token.
 */
object SupabaseHeaders {
    fun userBearer(apiKey: String, userAccessToken: String?): String? {
        val token = userAccessToken?.trim().orEmpty()
        if (token.isEmpty()) return null
        if (token == apiKey.trim()) return null
        if (token.startsWith("sb_publishable_") || token.startsWith("sb_secret_")) return null
        return "Bearer $token"
    }

    fun build(
        apiKey: String,
        userAccessToken: String? = null,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["apikey"] = apiKey.trim()
        headers["Content-Type"] = "application/json"
        val bearer = userBearer(apiKey, userAccessToken)
        if (bearer != null) headers["Authorization"] = bearer
        for ((k, v) in extra) headers[k] = v
        return headers
    }
}

/**
 * After HTTP 401, refresh the cloud session or end it.
 * The mesh signing key on [InstallRecord.mesh] is not part of this decision.
 */
object CloudAuthSession {
    fun onUnauthorized(record: InstallRecord, refreshResult: AuthCall?): InstallRecord {
        if (refreshResult is AuthCall.Ok && refreshResult.tokens.accessToken.isNotBlank()) {
            val expires = if (refreshResult.tokens.expiresInSeconds > 0) {
                System.currentTimeMillis() + refreshResult.tokens.expiresInSeconds * 1000L
            } else {
                record.tokenExpiresAtMs
            }
            return record.copy(
                accessToken = refreshResult.tokens.accessToken,
                refreshToken = refreshResult.tokens.refreshToken.ifBlank { record.refreshToken },
                cloud = CloudCredential.ACTIVE,
                tokenExpiresAtMs = expires
            )
        }
        return IdentityLifecycle.cloudSignOut(record)
    }
}

object AuthBackend {

    fun signUp(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        email: String,
        password: String,
        metadata: Map<String, String> = emptyMap(),
        codeChallenge: String? = null
    ): AuthCall {
        return call(
            http, baseUrl, anonKey, email, password,
            path = "/auth/v1/signup",
            query = redirectQuery(),
            metadata = metadata,
            codeChallenge = codeChallenge
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
        val url = baseUrl.trim().trimEnd('/') + "/auth/v1/resend" + redirectQuery()
        val body = JSONObject()
            .put("type", "signup")
            .put("email", email.trim())
            .put("redirect_to", AuthRedirect.CALLBACK)
            .toString()
        val response = try {
            http.post(url, SupabaseHeaders.build(anonKey), body)
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        return mapResponse(response) {
            AuthCall.Ok(AuthTokens("", "", 0L))
        }
    }

    /** Confirms the email from the link's token_hash. Does not follow a browser redirect. */
    fun verifyTokenHash(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        tokenHash: String,
        type: String
    ): AuthCall {
        if (!configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (tokenHash.isBlank() || type.isBlank()) return AuthCall.Failed("callback_rejected")
        val url = baseUrl.trim().trimEnd('/') + "/auth/v1/verify"
        val body = JSONObject().put("token_hash", tokenHash).put("type", type).toString()
        return postAuth(http, url, anonKey, body, allowEmptyToken = false)
    }

    /** Exchanges the PKCE code from the app link. The verifier never leaves this phone. */
    fun exchangePkce(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        code: String,
        codeVerifier: String
    ): AuthCall {
        if (!configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (code.isBlank() || codeVerifier.isBlank()) {
            return AuthCall.Failed("pkce_verifier_missing", diagnostic = "pkce_verifier_missing")
        }
        val url = baseUrl.trim().trimEnd('/') + "/auth/v1/token?grant_type=pkce"
        val body = JSONObject().put("auth_code", code).put("code_verifier", codeVerifier).toString()
        return postAuth(http, url, anonKey, body, allowEmptyToken = false)
    }

    fun fetchUser(http: HttpTransport, baseUrl: String, anonKey: String, accessToken: String): AuthCall {
        if (!configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (SupabaseHeaders.userBearer(anonKey, accessToken) == null) {
            return AuthCall.Failed("unauthorized")
        }
        val url = baseUrl.trim().trimEnd('/') + "/auth/v1/user"
        val response = try {
            http.get(url, SupabaseHeaders.build(anonKey, accessToken))
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        if (response.code == 401) return AuthCall.Failed("unauthorized")
        if (response.code !in 200..299) {
            return mapResponse(response) { AuthCall.Failed("bad_response") }
        }
        val parsed = parseTokens(response.body, allowEmptyToken = true)
        val verified = emailConfirmed(response.body)
        return when (parsed) {
            is AuthCall.PendingVerification -> if (verified) {
                AuthCall.Ok(AuthTokens(parsed.accountId, accessToken, 0L, true, ""))
            } else {
                parsed
            }
            is AuthCall.Ok -> parsed.copy(
                tokens = parsed.tokens.copy(
                    emailVerified = parsed.tokens.emailVerified || verified,
                    accessToken = parsed.tokens.accessToken.ifBlank { accessToken }
                )
            )
            else -> parsed
        }
    }

    fun refresh(http: HttpTransport, baseUrl: String, apiKey: String, refreshToken: String): AuthCall {
        if (!configured(baseUrl, apiKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (refreshToken.isBlank()) return AuthCall.Failed("unauthorized")
        val url = baseUrl.trimEnd('/') + "/auth/v1/token?grant_type=refresh_token"
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        return postAuth(http, url, apiKey, body, allowEmptyToken = false)
    }

    fun configured(baseUrl: String, anonKey: String): Boolean {
        val url = baseUrl.trim().trimEnd('/')
        val key = anonKey.trim()
        return key.isNotEmpty() && url.startsWith("https://") && url.length > "https://".length
    }

    private fun call(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        email: String,
        password: String,
        path: String,
        query: String,
        metadata: Map<String, String> = emptyMap(),
        codeChallenge: String? = null
    ): AuthCall {
        if (!configured(baseUrl, anonKey)) {
            return AuthCall.Failed("cloud_auth_not_configured")
        }
        if (!email.contains("@") || password.isEmpty() || (path.endsWith("/signup") && password.length < 8)) {
            return AuthCall.Failed("invalid_credentials_shape")
        }
        val url = baseUrl.trim().trimEnd('/') + path + query
        val payload = JSONObject().put("email", email.trim()).put("password", password)
        if (path.endsWith("/signup")) payload.put("redirect_to", AuthRedirect.CALLBACK)
        if (!codeChallenge.isNullOrBlank()) {
            payload.put("code_challenge", codeChallenge)
            payload.put("code_challenge_method", "s256")
        }
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
            http.post(url, SupabaseHeaders.build(anonKey), body)
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        return mapResponse(response) { parseTokens(response.body, allowEmptyToken) }
    }

    private fun mapResponse(response: HttpTransport.Response, onSuccess: () -> AuthCall): AuthCall {
        if (response.code in 200..299) return onSuccess()
        val mapped = AuthErrors.classify(response.body, response.code)
        val reason = when {
            mapped.reason != "unrecognized" -> mapped.reason
            response.code == 401 -> "unauthorized"
            else -> "unrecognized"
        }
        val retry = if (reason == "rate_limited" || response.code == 429) response.retryAfterSeconds else null
        return AuthCall.Failed(reason, retry, mapped.diagnostic)
    }

    private fun redirectQuery(): String {
        val encoded = URLEncoder.encode(AuthRedirect.CALLBACK, "UTF-8")
        return "?redirect_to=$encoded"
    }

    private fun emailConfirmed(body: String): Boolean {
        return try {
            val json = JSONObject(body)
            val user = if (json.has("user") && json.opt("user") is JSONObject) json.getJSONObject("user") else json
            val confirmed = user.optString("email_confirmed_at", "")
            confirmed.isNotBlank() && confirmed != "null"
        } catch (ignored: Throwable) {
            false
        }
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

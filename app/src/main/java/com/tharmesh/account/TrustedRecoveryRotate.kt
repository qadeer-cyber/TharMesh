// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Recovery upload through Edge (fresh auth + generation CAS). */
object TrustedRecoveryRotate {

    fun fetchGeneration(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String
    ): Int? {
        if (!AuthBackend.configured(baseUrl, anonKey)) return null
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return null
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/rest/v1/rpc/tharmesh_recovery_generation",
                headers,
                "{}"
            )
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299) return null
        return response.body.trim().toIntOrNull()
    }

    fun upload(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        sealed: IdentityRecovery.SealedIdentity,
        expectedGeneration: Int,
        grantId: String,
        requestId: String = UUID.randomUUID().toString()
    ): AuthCall {
        if (grantId.isBlank()) return AuthCall.Failed("grant_required")
        if (requestId.isBlank()) return AuthCall.Failed("request_id_required")
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (sealed.kdf != IdentityRecovery.KDF_SCRYPT) return AuthCall.Failed("bad_blob")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val ciphertextHash = sha256Hex(sealed.ciphertextB64)
        val body = JSONObject()
            .put("request_id", requestId)
            .put("ciphertext_sha256", ciphertextHash)
            .put("expected_generation", expectedGeneration)
            .put("kdf", sealed.kdf)
            .put("n", sealed.iterations)
            .put("r", sealed.r)
            .put("p", sealed.p)
            .put("salt_b64", sealed.saltB64)
            .put("iv_b64", sealed.ivB64)
            .put("ciphertext_b64", sealed.ciphertextB64)
            .put("grant_id", grantId)
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/functions/v1/recovery-rotate",
                headers,
                body.toString()
            )
        } catch (_: Throwable) {
            return AuthCall.Failed("network")
        }
        return when (response.code) {
            in 200..299 -> AuthCall.Ok(AuthTokens("", "", 0L))
            409 -> AuthCall.Failed("generation_conflict")
            403 -> AuthCall.Failed("fresh_auth_required")
            else -> AuthCall.Failed("http_${response.code}")
        }
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import org.json.JSONObject

/**
 * PostgREST writes for the account plane. Mesh traffic does not come here.
 * A blank or non-HTTPS project fails closed. The body for a recovery row is
 * built only from [IdentityRecovery.SealedIdentity].
 */
object SupabaseDirectory {

    fun upsertProfile(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        accountId: String,
        handle: String,
        displayName: String,
        avatarUrl: String?
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val normalized = HandlePolicy.normalize(handle) ?: return AuthCall.Failed("invalid_handle")
        val avatar = AvatarPolicy.normalize(avatarUrl)
        if (avatarUrl != null && avatarUrl.isNotBlank() && avatar == null) {
            return AuthCall.Failed("invalid_avatar")
        }
        val body = JSONObject()
            .put("account_id", accountId)
            .put("handle", normalized)
            .put("display_name", displayName.trim())
        if (avatar != null) body.put("avatar_url", avatar)
        val handleBody = JSONObject().put("handle", normalized).put("account_id", accountId)
        val headers = writeHeaders(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val handleResult = post(http, baseUrl.trimEnd('/') + "/rest/v1/handles", headers, handleBody.toString())
        if (handleResult is AuthCall.Failed) return handleResult
        return post(
            http,
            baseUrl.trimEnd('/') + "/rest/v1/profiles",
            headers,
            body.toString()
        )
    }

    fun putRecovery(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        accountId: String,
        sealed: IdentityRecovery.SealedIdentity
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        if (sealed.kdf != IdentityRecovery.KDF_SCRYPT) return AuthCall.Failed("bad_blob")
        val json = RecoveryUpload.body(accountId, sealed)
        if (!RlsPolicy.recoveryAccepts(json)) return AuthCall.Failed("bad_blob")
        val headers = writeHeaders(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        return post(
            http,
            baseUrl.trimEnd('/') + "/rest/v1/recovery_blobs",
            headers,
            json
        )
    }

    fun putDevice(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        accountId: String,
        certificate: DeviceCertificate
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val body = JSONObject()
            .put("account_id", accountId)
            .put("device_id", certificate.deviceId)
            .put("mesh_public_key", certificate.meshPublicKey)
            .put("device_public_key", certificate.devicePublicKey)
            .put("certificate", EnrollmentQr.encodeCertificate(certificate))
            .toString()
        val headers = writeHeaders(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        return post(
            http,
            baseUrl.trimEnd('/') + "/rest/v1/devices",
            headers,
            body
        )
    }

    private fun writeHeaders(apiKey: String, accessToken: String): Map<String, String>? {
        if (SupabaseHeaders.userBearer(apiKey, accessToken) == null) return null
        return SupabaseHeaders.build(
            apiKey,
            accessToken,
            mapOf("Prefer" to "resolution=merge-duplicates")
        )
    }

    private fun post(http: HttpTransport, url: String, headers: Map<String, String>, body: String): AuthCall {
        val response = try {
            http.post(url, headers, body)
        } catch (ignored: Throwable) {
            return AuthCall.Failed("network")
        }
        if (response.code == 409 || response.code == 23505) return AuthCall.Failed("handle_taken")
        val text = response.body
        if (text.contains("duplicate") || text.contains("23505")) return AuthCall.Failed("handle_taken")
        if (response.code !in 200..299) return AuthCall.Failed("http_${response.code}")
        return AuthCall.Ok(AuthTokens("", "", 0L))
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONObject

object TrustedDeviceRevoke {

    fun revoke(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        deviceId: String,
        grantId: String,
        activeDevicePublicKey: String
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("grant_id", grantId)
            .put("active_device_pubkey", activeDevicePublicKey)
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/functions/v1/device-revoke",
                headers,
                body.toString()
            )
        } catch (_: Throwable) {
            return AuthCall.Failed("network")
        }
        return when (response.code) {
            in 200..299 -> AuthCall.Ok(AuthTokens("", "", 0L))
            403 -> AuthCall.Failed("fresh_auth_required")
            else -> AuthCall.Failed("http_${response.code}")
        }
    }
}

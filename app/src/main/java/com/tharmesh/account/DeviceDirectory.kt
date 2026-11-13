// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONArray
import org.json.JSONObject

/** Cloud device directory via authenticated RPCs (no direct REST writes when guarded). */
object DeviceDirectory {

    data class DeviceRow(
        val deviceId: String,
        val meshPublicKey: String,
        val devicePublicKey: String,
        val revoked: Boolean
    )

    data class AccountEvidence(
        val identityState: String,
        val meshPublicKey: String?,
        val deviceCount: Int,
        val activeDeviceCount: Int,
        val hasRecoveryBlob: Boolean
    )

    fun list(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String
    ): List<DeviceRow>? {
        if (!AuthBackend.configured(baseUrl, anonKey)) return null
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return null
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/rest/v1/rpc/tharmesh_list_devices",
                headers,
                "{}"
            )
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299) return null
        val rows = JSONArray(response.body)
        return buildList {
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                add(
                    DeviceRow(
                        deviceId = o.getString("device_id"),
                        meshPublicKey = o.optString("mesh_public_key"),
                        devicePublicKey = o.optString("device_public_key"),
                        revoked = !o.isNull("revoked_at")
                    )
                )
            }
        }
    }

    fun revoke(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        deviceId: String
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val body = JSONObject().put("p_device_id", deviceId).toString()
        val response = try {
            http.post(baseUrl.trimEnd('/') + "/rest/v1/rpc/tharmesh_device_revoke", headers, body)
        } catch (_: Throwable) {
            return AuthCall.Failed("network")
        }
        return if (response.code in 200..299) AuthCall.Ok(AuthTokens("", "", 0L))
        else AuthCall.Failed("http_${response.code}")
    }

    fun fetchEvidence(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String
    ): AccountEvidence? {
        if (!AuthBackend.configured(baseUrl, anonKey)) return null
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return null
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/rest/v1/rpc/tharmesh_account_evidence",
                headers,
                "{}"
            )
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299) return null
        val rows = JSONArray(response.body)
        if (rows.length() < 1) return null
        val o = rows.getJSONObject(0)
        return AccountEvidence(
            identityState = o.getString("identity_state"),
            meshPublicKey = o.optString("mesh_public_key").takeIf { it.isNotBlank() },
            deviceCount = o.getInt("device_count"),
            activeDeviceCount = o.getInt("active_device_count"),
            hasRecoveryBlob = o.getBoolean("has_recovery_blob")
        )
    }
}

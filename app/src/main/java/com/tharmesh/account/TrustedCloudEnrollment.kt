// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONObject

/** Trusted enrollment via Edge Function (Web Crypto), not direct enrollment RPCs. */
object TrustedCloudEnrollment {

    fun deviceApproveMessage(
        accountId: String,
        newDevicePubkey: String,
        nonceB64: String,
        expiresAtEpochSec: Long
    ): ByteArray {
        val parts = listOf(
            "TM-DEVICE-APPROVE-v1",
            accountId,
            newDevicePubkey,
            nonceB64,
            expiresAtEpochSec.toString()
        )
        val out = java.io.ByteArrayOutputStream()
        parts.forEachIndexed { idx, s ->
            if (idx > 0) out.write(0x1F)
            out.write(s.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    fun enrollmentBegin(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        request: EnrollmentRequest,
        expiresAtEpochSec: Long
    ): AuthCall = postEdge(
        http, baseUrl, anonKey, accessToken,
        JSONObject()
            .put("operation", "begin")
            .put("device_id", request.deviceId)
            .put("new_device_pubkey", request.devicePublicKey)
            .put("nonce_b64", request.nonceB64)
            .put("request_sig_b64", request.deviceSignature)
            .put("mesh_public_key", request.meshPublicKey)
            .put("expires_at", java.time.Instant.ofEpochSecond(
                if (expiresAtEpochSec > 0L) expiresAtEpochSec else defaultExpiryEpochSec()
            ).toString())
    )

    fun enrollmentApprove(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        nonceB64: String,
        trustedDeviceId: String,
        newDevicePublicKey: String,
        approvalSigB64: String,
        expiresAtEpochSec: Long
    ): AuthCall = postEdge(
        http, baseUrl, anonKey, accessToken,
        JSONObject()
            .put("operation", "approve")
            .put("nonce_b64", nonceB64)
            .put("trusted_device_id", trustedDeviceId)
            .put("new_device_pubkey", newDevicePublicKey)
            .put("approval_sig_b64", approvalSigB64)
            .put("expires_epoch", expiresAtEpochSec)
    )

    fun enrollmentFinish(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        nonceB64: String,
        certificate: String
    ): AuthCall = postEdge(
        http, baseUrl, anonKey, accessToken,
        JSONObject()
            .put("operation", "finish")
            .put("nonce_b64", nonceB64)
            .put("certificate", certificate)
    )

    fun bootstrapDevice(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        deviceId: String,
        devicePublicKey: String,
        certificate: String? = null
    ): AuthCall = postEdge(
        http, baseUrl, anonKey, accessToken,
        JSONObject()
            .put("operation", "bootstrap_device")
            .put("device_id", deviceId)
            .put("device_pubkey", devicePublicKey)
            .put("certificate", certificate ?: "")
    )

    fun commitExplicitReset(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String
    ): AuthCall {
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        return postRpc(http, baseUrl, headers, "tharmesh_commit_explicit_reset", "{}")
    }

    fun defaultExpiryMs(): Long = System.currentTimeMillis() + java.util.concurrent.TimeUnit.MINUTES.toMillis(10)

    fun defaultExpiryEpochSec(): Long = defaultExpiryMs() / 1000L

    private fun postEdge(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        body: JSONObject
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val response = try {
            http.post(baseUrl.trimEnd('/') + "/functions/v1/device-enrollment", headers, body.toString())
        } catch (_: Throwable) {
            return AuthCall.Failed("network")
        }
        return if (response.code in 200..299) AuthCall.Ok(AuthTokens("", "", 0L))
        else AuthCall.Failed("http_${response.code}")
    }

    private fun postRpc(
        http: HttpTransport,
        baseUrl: String,
        headers: Map<String, String>,
        rpc: String,
        body: String
    ): AuthCall {
        val response = try {
            http.post(baseUrl.trimEnd('/') + "/rest/v1/rpc/$rpc", headers, body)
        } catch (_: Throwable) {
            return AuthCall.Failed("network")
        }
        return if (response.code in 200..299) AuthCall.Ok(AuthTokens("", "", 0L))
        else AuthCall.Failed("http_${response.code}")
    }
}

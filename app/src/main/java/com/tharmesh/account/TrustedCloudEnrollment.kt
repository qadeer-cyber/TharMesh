// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Server trusted-device enrollment RPCs (migration 004+). */
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
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val epoch = if (expiresAtEpochSec > 0L) expiresAtEpochSec else defaultExpiryMs() / 1000L
        val iso = java.time.Instant.ofEpochSecond(epoch).toString()
        val body = JSONObject()
            .put("p_device_id", request.deviceId)
            .put("p_new_device_pubkey", request.devicePublicKey)
            .put("p_nonce_b64", request.nonceB64)
            .put("p_request_sig_b64", request.deviceSignature)
            .put("p_expires_at", iso)
            .toString()
        return postRpc(http, baseUrl, headers, "tharmesh_enrollment_begin", body)
    }

    fun enrollmentApprove(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        nonceB64: String,
        trustedDeviceId: String,
        approvalSigB64: String
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val body = JSONObject()
            .put("p_nonce_b64", nonceB64)
            .put("p_trusted_device_id", trustedDeviceId)
            .put("p_approval_sig_b64", approvalSigB64)
            .toString()
        return postRpc(http, baseUrl, headers, "tharmesh_enrollment_approve", body)
    }

    fun enrollmentFinish(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        nonceB64: String,
        certificate: String
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val body = JSONObject()
            .put("p_nonce_b64", nonceB64)
            .put("p_certificate", certificate)
            .toString()
        return postRpc(http, baseUrl, headers, "tharmesh_enrollment_finish", body)
    }

    fun bootstrapDevice(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        deviceId: String,
        devicePublicKey: String,
        certificate: String? = null
    ): AuthCall {
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val body = JSONObject()
            .put("p_device_id", deviceId)
            .put("p_device_pubkey", devicePublicKey)
            .put("p_certificate", certificate ?: "")
            .toString()
        return postRpc(http, baseUrl, headers, "tharmesh_bootstrap_device", body)
    }

    fun commitExplicitReset(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String
    ): AuthCall {
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        return postRpc(http, baseUrl, headers, "tharmesh_commit_explicit_reset", "{}")
    }

    fun defaultExpiryMs(): Long = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)

    fun epochSecondsFromIso(iso: String): Long =
        java.time.Instant.parse(iso).epochSecond

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

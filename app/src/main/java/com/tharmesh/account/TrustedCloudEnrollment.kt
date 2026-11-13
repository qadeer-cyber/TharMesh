// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import com.tharmesh.identity.CryptoIdentity
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

    fun enrollFinishMessage(
        accountId: String,
        meshPublicKey: String,
        devicePublicKey: String,
        deviceId: String,
        nonceB64: String
    ): ByteArray = canonical(
        "TM-ENROLL-FINISH-v1",
        accountId,
        meshPublicKey,
        devicePublicKey,
        deviceId,
        nonceB64,
        "0"
    )

    fun bootstrapMessage(
        accountId: String,
        challenge: String,
        meshPublicKey: String,
        deviceId: String,
        devicePublicKey: String
    ): ByteArray = (
        "tharmesh:device:bootstrap:v1\n$accountId\n$challenge\n$meshPublicKey\n$deviceId\n$devicePublicKey"
    ).toByteArray(Charsets.UTF_8)

    fun enrollmentFinish(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        nonceB64: String,
        certificate: String,
        finishSigB64: String
    ): AuthCall = postEdge(
        http, baseUrl, anonKey, accessToken,
        JSONObject()
            .put("operation", "finish")
            .put("nonce_b64", nonceB64)
            .put("certificate", certificate)
            .put("finish_sig_b64", finishSigB64)
    )

    fun fetchBootstrapChallenge(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String
    ): String? {
        if (!AuthBackend.configured(baseUrl, anonKey)) return null
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return null
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/functions/v1/device-enrollment",
                headers,
                JSONObject().put("operation", "bootstrap_challenge").toString()
            )
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299) return null
        return JSONObject(response.body).optString("challenge").takeIf { it.isNotBlank() }
    }

    fun bootstrapCommit(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        accountId: String,
        mesh: CryptoIdentity,
        device: DeviceSigner,
        deviceId: String,
        challenge: String
    ): AuthCall {
        val msg = bootstrapMessage(accountId, challenge, mesh.publicKeyBase64, deviceId, device.publicKeyBase64)
        val meshDer = Base64Url.decode(mesh.sign(msg))
        val meshSig = meshDer?.let { Base64Url.encode(IdentityProvisioning.p256Signature(it)) } ?: return AuthCall.Failed("network")
        return postEdge(
            http, baseUrl, anonKey, accessToken,
            JSONObject()
                .put("operation", "bootstrap_commit")
                .put("device_id", deviceId)
                .put("device_pubkey", device.publicKeyBase64)
                .put("mesh_public_key", mesh.publicKeyBase64)
                .put("challenge", challenge)
                .put("mesh_sig_b64", meshSig)
                .put("device_sig_b64", device.sign(msg))
        )
    }

    private fun canonical(vararg parts: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEachIndexed { idx, s ->
            if (idx > 0) out.write(0x1F)
            out.write(s.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
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

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONObject

object TrustedDeviceRevoke {

    fun deviceRevokeMessage(
        accountId: String,
        targetDeviceId: String,
        grantId: String,
        callerDevicePubkey: String,
        proofTsSec: Long
    ): ByteArray {
        val parts = listOf(
            "TM-DEVICE-REVOKE-v1",
            accountId,
            targetDeviceId,
            grantId,
            callerDevicePubkey,
            proofTsSec.toString()
        )
        val out = java.io.ByteArrayOutputStream()
        parts.forEachIndexed { idx, s ->
            if (idx > 0) out.write(0x1F)
            out.write(s.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    fun revoke(
        http: HttpTransport,
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        accountId: String,
        deviceId: String,
        grantId: String,
        signer: DeviceSigner
    ): AuthCall {
        if (accountId.isBlank()) return AuthCall.Failed("account_required")
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val proofTs = System.currentTimeMillis() / 1000
        val msg = deviceRevokeMessage(accountId, deviceId, grantId, signer.publicKeyBase64, proofTs)
        val proofSig = signer.sign(msg)
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("grant_id", grantId)
            .put("caller_device_pubkey", signer.publicKeyBase64)
            .put("proof_sig_b64", proofSig)
            .put("proof_ts", proofTs)
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

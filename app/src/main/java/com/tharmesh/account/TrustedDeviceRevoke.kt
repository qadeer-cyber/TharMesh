// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.json.JSONObject

object TrustedDeviceRevoke {

    fun deviceRevokeChallengeMessage(
        accountId: String,
        sessionId: String,
        requestingDeviceId: String,
        targetDeviceId: String,
        grantId: String,
        challengeId: String
    ): ByteArray {
        val parts = listOf(
            "TM-DEVICE-REVOKE-CHALLENGE-v1",
            accountId,
            sessionId,
            requestingDeviceId,
            targetDeviceId,
            grantId,
            challengeId
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
        requestingDeviceId: String,
        targetDeviceId: String,
        grantId: String,
        signer: DeviceSigner
    ): AuthCall {
        if (accountId.isBlank()) return AuthCall.Failed("account_required")
        if (requestingDeviceId.isBlank()) return AuthCall.Failed("requesting_device_required")
        if (!AuthBackend.configured(baseUrl, anonKey)) return AuthCall.Failed("cloud_auth_not_configured")
        val headers = SupabaseHeaders.build(anonKey, accessToken) ?: return AuthCall.Failed("unauthorized")
        val challengeBody = JSONObject()
            .put("operation", "challenge")
            .put("target_device_id", targetDeviceId)
            .put("grant_id", grantId)
            .put("requesting_device_id", requestingDeviceId)
        val challengeRes = try {
            http.post(
                baseUrl.trimEnd('/') + "/functions/v1/device-revoke",
                headers,
                challengeBody.toString()
            )
        } catch (_: Throwable) {
            return AuthCall.Failed("network")
        }
        if (challengeRes.code !in 200..299) {
            return when (challengeRes.code) {
                403 -> AuthCall.Failed("fresh_auth_required")
                else -> AuthCall.Failed("http_${challengeRes.code}")
            }
        }
        val challengeJson = org.json.JSONObject(challengeRes.body)
        val challengeId = challengeJson.optString("challenge_id")
        val bound = challengeJson.optJSONObject("bound") ?: return AuthCall.Failed("challenge_malformed")
        val sessionId = bound.optString("session_id")
        if (challengeId.isBlank() || sessionId.isBlank()) return AuthCall.Failed("challenge_malformed")
        val msg = deviceRevokeChallengeMessage(
            accountId,
            sessionId,
            requestingDeviceId,
            targetDeviceId,
            grantId,
            challengeId
        )
        val commitBody = JSONObject()
            .put("operation", "commit")
            .put("challenge_id", challengeId)
            .put("target_device_id", targetDeviceId)
            .put("proof_sig_b64", signer.sign(msg))
        val response = try {
            http.post(
                baseUrl.trimEnd('/') + "/functions/v1/device-revoke",
                headers,
                commitBody.toString()
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

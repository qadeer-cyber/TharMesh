package com.tharmesh.identity

import org.json.JSONObject

data class IdentityQrPayload(
    val userId: String,
    val name: String,
    val publicKeyBase64: String
)

object QrCodec {

    fun encode(payload: IdentityQrPayload): String {
        val json = JSONObject()
        json.put("userId", payload.userId)
        json.put("name", payload.name)
        json.put("pubKey", payload.publicKeyBase64)
        return json.toString()
    }

    fun decode(raw: String): IdentityQrPayload? {
        return try {
            val json = JSONObject(raw)
            IdentityQrPayload(
                userId = json.optString("userId", ""),
                name = json.optString("name", ""),
                publicKeyBase64 = json.optString("pubKey", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }
}

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import com.tharmesh.identity.CryptoIdentity
import org.json.JSONArray
import org.json.JSONObject

/** Uses only the public Supabase key and the authenticated user's access token. */
class IdentityProvisioning(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val publicKey: String,
    private val tokens: AuthTokens
) {
    sealed class Result {
        data class Activated(val identity: CryptoIdentity) : Result()
        data class RecoveryRequired(val canonicalKey: String? = null) : Result()
        object Retry : Result()
    }

    fun activate(stage: (allowCreate: Boolean) -> CryptoIdentity?): Result = try {
        provision(stage)
    } catch (_: Exception) {
        Result.Retry
    }

    private fun provision(stage: (Boolean) -> CryptoIdentity?): Result {
        if (!tokens.emailVerified || !AuthBackend.configured(baseUrl, publicKey) ||
            SupabaseHeaders.userBearer(publicKey, tokens.accessToken) == null
        ) return Result.Retry
        val state = readState(post("/rest/v1/rpc/tharmesh_identity_state", JSONObject()))
        when (state.getString("state")) {
            "active" -> {
                val staged = stage(false) ?: return recovery(state)
                return if (matches(state, staged)) Result.Activated(staged) else recovery(state)
            }
            "legacy_unknown", "recovery_required" -> return recovery(state)
            "unprovisioned" -> Unit
            else -> return Result.Retry
        }
        val identity = stage(true) ?: return Result.Retry
        val key = identity.publicKeyBase64
        val request = JSONObject().put("operation", "challenge").put("key", key)
        val nonce = JSONObject(post("/functions/v1/identity-proof", request)).getString("nonce")
        require(nonce.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        val message = "tharmesh:identity:register:v1\n${tokens.accountId}\n$nonce\n$key"
        val der = requireNotNull(Base64Url.decode(identity.sign(message.toByteArray(Charsets.UTF_8))))
        val signature = Base64Url.encode(p256Signature(der))
        request.put("operation", "finish").put("nonce", nonce).put("signature", signature)
        val committed = readState(post("/functions/v1/identity-proof", request))
        return when {
            matches(committed, identity) -> Result.Activated(identity)
            committed.getString("state") == "recovery_required" -> recovery(committed)
            else -> Result.Retry
        }
    }

    private fun recovery(state: JSONObject): Result.RecoveryRequired =
        Result.RecoveryRequired(state.optString("mesh_public_key").takeIf { it.isNotBlank() && it != "null" })

    private fun post(path: String, body: JSONObject): String {
        val response = http.post(
            baseUrl.trimEnd('/') + path,
            SupabaseHeaders.build(publicKey, tokens.accessToken),
            body.toString()
        )
        check(response.code in 200..299)
        return response.body
    }

    private fun readState(body: String): JSONObject {
        val rows = JSONArray(body)
        require(rows.length() == 1)
        return rows.getJSONObject(0)
    }

    private fun matches(state: JSONObject, identity: CryptoIdentity): Boolean =
        state.optString("state") == "active" && state.optInt("generation") == 1 &&
            state.optString("mesh_public_key") == identity.publicKeyBase64

    companion object {
        /** Convert the JCA DER ECDSA signature to Web Crypto's fixed-width r || s. */
        internal fun p256Signature(der: ByteArray): ByteArray {
            require(der.size in 8..72 && der[0] == 0x30.toByte() && der[1].toInt() == der.size - 2)
            val output = ByteArray(64)
            var cursor = 2
            for (offset in listOf(0, 32)) {
                require(cursor + 2 <= der.size && der[cursor++] == 2.toByte())
                val length = der[cursor++].toInt()
                require(length in 1..33 && cursor + length <= der.size)
                require(der[cursor] >= 0)
                val padding = if (length > 1 && der[cursor] == 0.toByte()) 1 else 0
                if (padding == 1) require(der[cursor + 1] < 0)
                val width = length - padding
                require(width <= 32)
                der.copyInto(output, offset + 32 - width, cursor + padding, cursor + length)
                cursor += length
            }
            require(cursor == der.size)
            return output
        }
    }
}

package com.tharmesh.identity

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class IdentityQrPayload(
    val userId: String,
    val name: String,
    val publicKeyBase64: String
)

/**
 * Encodes / decodes the TharMesh identity QR payload.
 *
 * Two wire forms are supported:
 *
 *  1. **URI form (Stage 7 PR D, default for new QRs):**
 *     `tharmesh://invite?uid=<userId>&pub=<publicKeyBase64>&name=<displayName>`.
 *     Shorter, denser as a QR (especially without `pub`) and tappable
 *     from system QR readers / chat apps as a normal deep link.
 *
 *  2. **JSON form (legacy):**
 *     `{"userId":"...","name":"...","pubKey":"..."}`. Produced by
 *     pre-PR-D builds; we keep decoding it forever so older QRs still
 *     scan after users update.
 *
 * [encode] always emits the URI form. [decode] tries URI first then
 * falls back to JSON. Empty / unparseable input yields `null`.
 *
 * Implementation note: encoding/decoding is pure JVM (URLEncoder /
 * URLDecoder) — we deliberately avoid `android.net.Uri` so this object
 * is unit-testable on the JVM without Robolectric. The wire format is
 * a straight `application/x-www-form-urlencoded` query string after
 * the fixed `tharmesh://invite?` prefix, which is byte-identical to
 * what `android.net.Uri.Builder` would emit for the same inputs.
 */
object QrCodec {

    private const val URI_SCHEME = "tharmesh"
    private const val URI_HOST = "invite"
    private const val URI_PARAM_UID = "uid"
    private const val URI_PARAM_PUB = "pub"
    private const val URI_PARAM_NAME = "name"

    /** Stable URI prefix usable by callers that need to pattern-match. */
    const val INVITE_URI_PREFIX: String = "$URI_SCHEME://$URI_HOST"

    fun encode(payload: IdentityQrPayload): String {
        val params = mutableListOf<Pair<String, String>>()
        params += URI_PARAM_UID to payload.userId
        if (payload.publicKeyBase64.isNotBlank()) {
            params += URI_PARAM_PUB to payload.publicKeyBase64
        }
        if (payload.name.isNotBlank()) {
            params += URI_PARAM_NAME to payload.name
        }
        val query = params.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, Charsets.UTF_8.name())
        }
        return "$INVITE_URI_PREFIX?$query"
    }

    fun decode(raw: String): IdentityQrPayload? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return decodeUri(text) ?: decodeJson(text)
    }

    private fun decodeUri(raw: String): IdentityQrPayload? {
        // Require either an exact match against the prefix or the
        // prefix followed by '?'. A bare prefix check would also let
        // through `tharmesh://invitations…` or `tharmesh://invite-foo…`.
        if (!raw.equals(INVITE_URI_PREFIX, ignoreCase = true) &&
            !raw.startsWith("$INVITE_URI_PREFIX?", ignoreCase = true)
        ) return null
        val queryStart = raw.indexOf('?')
        val query = if (queryStart >= 0 && queryStart + 1 < raw.length) {
            raw.substring(queryStart + 1)
        } else {
            ""
        }
        val params = parseQuery(query)
        val userId = params[URI_PARAM_UID].orEmpty()
        if (userId.isBlank()) return null
        return IdentityQrPayload(
            userId = userId,
            name = params[URI_PARAM_NAME].orEmpty(),
            publicKeyBase64 = params[URI_PARAM_PUB].orEmpty()
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey: String
            val rawValue: String
            if (eq < 0) {
                rawKey = pair
                rawValue = ""
            } else {
                rawKey = pair.substring(0, eq)
                rawValue = pair.substring(eq + 1)
            }
            val key = decodeOrFallback(rawKey)
            // Last-write-wins matches android.net.Uri.getQueryParameter
            // semantics for repeated keys.
            out[key] = decodeOrFallback(rawValue)
        }
        return out
    }

    private fun decodeOrFallback(raw: String): String =
        runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)

    private fun decodeJson(raw: String): IdentityQrPayload? {
        return try {
            val json = JSONObject(raw)
            val userId = json.optString("userId", "")
            if (userId.isBlank()) return null
            IdentityQrPayload(
                userId = userId,
                name = json.optString("name", ""),
                publicKeyBase64 = json.optString("pubKey", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }
}

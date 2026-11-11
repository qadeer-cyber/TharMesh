// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Email confirmation returns to this app, not to a website.
 * The Supabase Site URL and redirect allow-list must include [CALLBACK].
 */
object AuthRedirect {
    const val CALLBACK: String = "tharmesh://auth/callback"

    private val ALLOWED_TYPES = setOf("signup", "email", "magiclink", "recovery", "invite")

    sealed class Parsed {
        data class Rejected(val reason: String) : Parsed()
        data class Accepted(
            val code: String?,
            val tokenHash: String?,
            val type: String?,
            val accessToken: String?,
            val refreshToken: String?
        ) : Parsed()
    }

    data class Pkce(val verifier: String, val challenge: String)

    fun generatePkce(random: SecureRandom = SecureRandom()): Pkce {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val verifier = base64Url(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64Url(digest))
    }

    /**
     * Accept only tharmesh://auth/callback. Query and fragment are both read.
     * A foreign host, a publishable key used as a token, or an unknown type is rejected.
     * This function does not log the URI.
     */
    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val hash = trimmed.indexOf('#')
        val before = if (hash >= 0) trimmed.substring(0, hash) else trimmed
        val fragment = if (hash >= 0) trimmed.substring(hash + 1) else ""
        val schemeSep = before.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = before.substring(0, schemeSep)
        if (!scheme.equals("tharmesh", ignoreCase = true)) return null
        val rest = before.substring(schemeSep + 3)
        val slash = rest.indexOf('/')
        val hostAndQuery = if (slash >= 0) rest.substring(0, slash) else rest
        val host = hostAndQuery.substringBefore('?')
        if (!host.equals("auth", ignoreCase = true)) return null
        val afterHost = if (slash >= 0) rest.substring(slash) else "/"
        val path = afterHost.substringBefore('?')
        if (path != "/callback" && path != "/callback/") return null
        val query = decodeParams(afterHost.substringAfter('?', ""))
        val frag = decodeParams(fragment)
        fun pick(name: String): String? = query[name] ?: frag[name]
        val error = pick("error")
        val errorCode = pick("error_code")
        if (!error.isNullOrBlank() || !errorCode.isNullOrBlank()) {
            val body = JSONObject()
                .put("error", error ?: "")
                .put("error_code", errorCode ?: "")
                .put("error_description", pick("error_description") ?: "")
                .toString()
            return Parsed.Rejected(AuthErrors.classify(body, 400).reason)
        }
        val type = pick("type")
        if (type != null && type !in ALLOWED_TYPES) return null
        val access = pick("access_token")
        if (access != null && (access.startsWith("sb_publishable_") || access.startsWith("sb_secret_"))) {
            return null
        }
        val code = pick("code")?.takeIf { it.isNotBlank() }
        val tokenHash = (pick("token_hash") ?: pick("token"))?.takeIf { it.isNotBlank() }
        val refresh = pick("refresh_token")?.takeIf { it.isNotBlank() }
        if (code == null && tokenHash == null && access.isNullOrBlank()) return null
        return Parsed.Accepted(code, tokenHash, type, access, refresh)
    }

    private fun decodeParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = urlDecode(if (eq >= 0) part.substring(0, eq) else part)
            val value = urlDecode(if (eq >= 0) part.substring(eq + 1) else "")
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (ignored: Throwable) {
            value
        }
    }

    private fun base64Url(bytes: ByteArray): String {
        return Base64Url.encode(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
    }
}

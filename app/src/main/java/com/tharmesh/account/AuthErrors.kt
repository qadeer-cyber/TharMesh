// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import org.json.JSONObject

/**
 * Turns a GoTrue error body into a stable reason plus a short diagnostic code.
 * The diagnostic is safe to show on screen: it is a slug, never an email, token, or key.
 */
object AuthErrors {
    data class Mapped(val reason: String, val diagnostic: String)

    fun classify(body: String, httpCode: Int): Mapped {
        val fields = collect(body)
        val blob = fields.joinToString(" ").lowercase()
        val reason = when {
            blob.hasAny("email_not_confirmed", "email not confirmed") -> "email_unverified"
            blob.hasAny("otp_expired", "token has expired", "invalid otp", "otp is invalid", "link is invalid") ->
                "otp_expired"
            blob.hasAny("bad_code_verifier", "flow_state_not_found", "flow_state_expired") -> "pkce_expired"
            blob.hasAny(
                "over_email_send_rate_limit",
                "over_request_rate_limit",
                "rate limit"
            ) -> "rate_limited"
            blob.hasAny("already confirmed", "already_confirmed") -> "already_confirmed"
            blob.hasAny("user_already_exists", "email_exists", "already been registered", "already registered") ->
                "account_exists"
            blob.hasAny("invalid_credentials", "invalid login credentials", "invalid_grant") -> "invalid_credentials"
            blob.hasAny("weak_password") -> "weak_password"
            blob.hasAny("email_address_invalid", "unable to validate email address") -> "email_address_invalid"
            blob.hasAny("signup_disabled", "signups not allowed", "signup is disabled") -> "signup_disabled"
            blob.hasAny("validation_failed") -> "validation_failed"
            else -> "unrecognized"
        }
        val diagnostic = slug(preferredCode(body))
            ?: slug(stableCode(reason))
            ?: "http_$httpCode"
        return Mapped(reason, diagnostic)
    }

    private fun preferredCode(body: String): String? {
        if (body.isBlank()) return null
        return try {
            fromObject(JSONObject(body))
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun fromObject(json: JSONObject): String? {
        for (key in listOf("error_code", "code", "error")) {
            when (val value = json.opt(key)) {
                is String -> if (slug(value) != null && value.any { it.isLetter() }) return value
                is JSONObject -> fromObject(value)?.let { return it }
            }
        }
        return null
    }

    private fun stableCode(reason: String): String? {
        return when (reason) {
            "email_unverified" -> "email_not_confirmed"
            "otp_expired" -> "otp_expired"
            "pkce_expired" -> "pkce_expired"
            "rate_limited" -> "over_request_rate_limit"
            "already_confirmed" -> "already_confirmed"
            "account_exists" -> "email_exists"
            "invalid_credentials" -> "invalid_credentials"
            "weak_password" -> "weak_password"
            "email_address_invalid" -> "email_address_invalid"
            "signup_disabled" -> "signup_disabled"
            "validation_failed" -> "validation_failed"
            else -> null
        }
    }

    /** Only a short slug. Anything that could be a secret becomes null. */
    fun slug(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lower = raw.trim().lowercase()
        if (lower.contains("@") || lower.contains("sb_") || lower.contains("bearer") || lower.startsWith("eyj")) {
            return null
        }
        if (lower.any { it.isWhitespace() }) return null
        val cleaned = lower.replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        if (cleaned.length !in 3..48) return null
        if (cleaned.all { it.isDigit() || it == '_' }) return null
        return cleaned
    }

    private fun collect(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        return try {
            val json = JSONObject(body)
            val out = ArrayList<String>()
            fun add(value: Any?) {
                when (value) {
                    is String -> if (value.isNotBlank()) out.add(value)
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext()) add(value.opt(keys.next()))
                    }
                }
            }
            val keys = json.keys()
            while (keys.hasNext()) add(json.opt(keys.next()))
            out
        } catch (ignored: Throwable) {
            listOf(body)
        }
    }

    private fun String.hasAny(vararg needles: String): Boolean {
        return needles.any { this.contains(it) }
    }
}

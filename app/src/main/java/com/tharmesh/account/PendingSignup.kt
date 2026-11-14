// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import android.content.Context
import org.json.JSONObject

/** Email confirmation in progress. The password is never stored. */
data class PendingSignup(
    val email: String,
    val accountId: String,
    val codeVerifier: String
)

object PendingSignupCodec {
    fun encode(pending: PendingSignup): String {
        return JSONObject()
            .put("email", pending.email)
            .put("accountId", pending.accountId)
            .put("codeVerifier", pending.codeVerifier)
            .toString()
    }

    fun decode(raw: String?): PendingSignup? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            if (json.has("password")) return null
            val email = json.optString("email", "")
            if (!email.contains("@")) return null
            PendingSignup(
                email = email,
                accountId = json.optString("accountId", ""),
                codeVerifier = json.optString("codeVerifier", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }
}

object PendingSignupStore {
    private const val PREFS = "tharmesh_pending_signup"
    private const val KEY = "pending"

    fun save(context: Context, pending: PendingSignup) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, PendingSignupCodec.encode(pending))
            .apply()
    }

    fun read(context: Context): PendingSignup? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return PendingSignupCodec.decode(raw)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

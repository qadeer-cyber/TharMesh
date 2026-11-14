// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import android.content.Context

/** Rejects enrollment certificates that reuse a nonce already accepted on this phone. */
object EnrollmentReplayGuard {
    private const val PREFS = "tharmesh_enrollment_nonces"
    private const val MAX = 64

    fun markConsumed(context: Context, accountId: String, nonceB64: String): Boolean {
        if (nonceB64.isBlank() || accountId.isBlank()) return false
        val key = "nonce_${accountId}_$nonceB64"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(key, false)) return false
        val editor = prefs.edit().putBoolean(key, true)
        val all = prefs.all.keys.filter { it.startsWith("nonce_") }
        if (all.size >= MAX) {
            all.sorted().take(all.size - MAX + 1).forEach { editor.remove(it) }
        }
        editor.apply()
        return true
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import android.content.Context

/** Tracks recent successful authentication for sensitive settings actions. */
object RecentReauth {
    private const val PREFS = "tharmesh_recent_reauth"
    private const val WINDOW_MS = 15 * 60 * 1000L

    fun mark(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    fun isFresh(context: Context): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("at", 0L)
        return at > 0 && System.currentTimeMillis() - at < WINDOW_MS
    }
}

package com.tharmesh.diagnostics

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted toggle for Stage 5.1 Field Test Mode. When enabled, a Diagnostics
 * entry point becomes reachable from the Settings screen. The mode itself does
 * NOT change any mesh behaviour — the [DiagnosticsCollector] runs
 * unconditionally so counters are already warm when the user opens the screen.
 *
 * Storage is a dedicated SharedPreferences file, separate from the user
 * profile prefs, so signing out does NOT clear the diagnostic toggle (field
 * testers commonly sign in/out repeatedly).
 */
object FieldTestMode {

    private const val PREFS_NAME = "tharmesh_field_test"
    private const val KEY_ENABLED = "field_test_enabled"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun toggle(context: Context): Boolean {
        val next = !isEnabled(context)
        setEnabled(context, next)
        return next
    }
}

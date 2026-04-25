package com.tharmesh.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.tharmesh.dtn.RetryConfig

/**
 * Persisted toggle for Stage 5.1 Field Test Mode. When enabled, a Diagnostics
 * entry point becomes reachable from the Settings screen. The mode itself does
 * NOT change any mesh behaviour — the [DiagnosticsCollector] runs
 * unconditionally so counters are already warm when the user opens the screen.
 *
 * Stage 5.3 — adds two retry-tuning toggles surfaced in the Diagnostics screen:
 *  - [isBackoffDisabled]: replaces the default [RetryConfig.DEFAULT] curve with
 *    [RetryConfig.FIELD_TEST_FLAT] (fixed 1 s, no growth, no jitter) so retries
 *    behave like the pre-Stage-5.2 sweep for A/B comparison.
 *  - [isHighFrequencyForced]: replaces the default with
 *    [RetryConfig.FIELD_TEST_FAST] (1 s curve, 250 ms tick) so field testers
 *    see retry behaviour in seconds rather than minutes.
 *
 * Both toggles take effect on the next [com.tharmesh.TharMeshApp.ensureMeshReady]
 * call (i.e. next process restart, OR next sign-out → sign-in cycle). The UI
 * makes this explicit. We deliberately do NOT hot-swap the running engine's
 * config: doing so would require the mesh stack to support per-bundle state
 * teardown and the cost/benefit isn't worth the complexity for a debug toggle.
 *
 * Storage is a dedicated SharedPreferences file, separate from the user
 * profile prefs, so signing out does NOT clear the diagnostic toggle (field
 * testers commonly sign in/out repeatedly).
 */
object FieldTestMode {

    private const val PREFS_NAME = "tharmesh_field_test"
    private const val KEY_ENABLED = "field_test_enabled"
    private const val KEY_DISABLE_BACKOFF = "field_test_disable_backoff"
    private const val KEY_FORCE_HIGH_FREQ = "field_test_force_high_freq"

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

    /** Stage 5.3 — A/B knob: replace exponential backoff with a flat 1 s curve. */
    fun isBackoffDisabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLE_BACKOFF, false)

    fun setBackoffDisabled(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLE_BACKOFF, disabled).apply()
    }

    /** Stage 5.3 — force the FIELD_TEST_FAST curve (1 s flat, 250 ms tick). */
    fun isHighFrequencyForced(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_HIGH_FREQ, false)

    fun setHighFrequencyForced(context: Context, forced: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_HIGH_FREQ, forced).apply()
    }

    /**
     * Stage 5.3 — choose the [RetryConfig] to feed into [com.tharmesh.dtn.MeshEngine]
     * / [com.tharmesh.data.MessageRepository] based on the field test toggles.
     * Precedence (highest → lowest): FAST > FLAT > DEFAULT. Both toggles being
     * on at once is a configuration error (the UI prevents it via radio-style
     * exclusion), but FAST wins in that case as the more aggressive curve.
     */
    fun resolveRetryConfig(context: Context): RetryConfig = when {
        isHighFrequencyForced(context) -> RetryConfig.FIELD_TEST_FAST
        isBackoffDisabled(context) -> RetryConfig.FIELD_TEST_FLAT
        else -> RetryConfig.DEFAULT
    }
}

package com.tharmesh.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.tharmesh.data.UserPrefs

/**
 * Stage 6.1 — process-wide theme-mode controller.
 *
 * The user picks System Default / Light / Dark in [com.tharmesh.ui.settings.SettingsFragment].
 * The choice is persisted via [UserPrefs.setThemeMode] and applied here by
 * delegating to [AppCompatDelegate.setDefaultNightMode]; AppCompat then drives
 * the DayNight resolution and recreates running activities cleanly without any
 * manual `recreate()` calls (which would fight Fragment state restoration).
 *
 * Apply once at application start (from `TharMeshApp.onCreate`) and again from
 * the settings picker. There's no need to call this on every Activity.
 */
object ThemeManager {

    /** Persisted choice. Stable enum names; do not rename without a migration. */
    enum class Mode { SYSTEM, LIGHT, DARK }

    /**
     * Apply [mode] globally. Idempotent — calling twice with the same mode is
     * a no-op inside AppCompat's setDefaultNightMode.
     */
    fun apply(mode: Mode) {
        val nightMode = when (mode) {
            Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /** Read the persisted choice and apply it. Safe to call before any UI exists. */
    fun applyFromPrefs(context: Context) {
        apply(UserPrefs.getThemeMode(context))
    }

    /** Persist + apply atomically. Use this from the settings picker. */
    fun setAndApply(context: Context, mode: Mode) {
        UserPrefs.setThemeMode(context, mode)
        apply(mode)
    }
}

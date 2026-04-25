package com.tharmesh.ui.theme

import android.content.Context
import android.content.SharedPreferences
import com.tharmesh.data.UserPrefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Stage 6.1 — Mockito-only round-trip test for the persisted Theme Mode
 * preference. Mirrors the FieldTestModeTest pattern (no Robolectric needed)
 * so it runs under JDK 11 + Gradle 6.5 + AGP 4.1.3 without any new deps.
 */
class ThemeModePrefsTest {

    private fun fakeContextWithPrefs(): Pair<Context, HashMap<String, Any?>> {
        val store = HashMap<String, Any?>()

        val editor = mock(SharedPreferences.Editor::class.java)
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as String?
            editor
        }.`when`(editor).putString(anyString(), anyString())
        doAnswer { }.`when`(editor).apply()

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        doAnswer { inv ->
            store[inv.arguments[0] as String] as String?
        }.`when`(prefs).getString(anyString(), isNull())

        val ctx = mock(Context::class.java)
        `when`(ctx.getSharedPreferences("tharmesh_user_prefs", Context.MODE_PRIVATE))
            .thenReturn(prefs)
        return ctx to store
    }

    @Test
    fun `default theme mode is SYSTEM when nothing has been persisted yet`() {
        val (ctx, _) = fakeContextWithPrefs()
        assertEquals(ThemeManager.Mode.SYSTEM, UserPrefs.getThemeMode(ctx))
    }

    @Test
    fun `setThemeMode LIGHT persists the enum name and round-trips`() {
        val (ctx, store) = fakeContextWithPrefs()
        UserPrefs.setThemeMode(ctx, ThemeManager.Mode.LIGHT)
        assertEquals("LIGHT", store["theme_mode"])
        assertEquals(ThemeManager.Mode.LIGHT, UserPrefs.getThemeMode(ctx))
    }

    @Test
    fun `setThemeMode DARK persists the enum name and round-trips`() {
        val (ctx, store) = fakeContextWithPrefs()
        UserPrefs.setThemeMode(ctx, ThemeManager.Mode.DARK)
        assertEquals("DARK", store["theme_mode"])
        assertEquals(ThemeManager.Mode.DARK, UserPrefs.getThemeMode(ctx))
    }

    @Test
    fun `unrecognized persisted value falls back to SYSTEM (forward compat)`() {
        val (ctx, store) = fakeContextWithPrefs()
        store["theme_mode"] = "FROM_THE_FUTURE"
        assertEquals(ThemeManager.Mode.SYSTEM, UserPrefs.getThemeMode(ctx))
    }
}

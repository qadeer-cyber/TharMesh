package com.tharmesh.diagnostics

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Robolectric-free unit test — uses Mockito-core (already on the classpath) to
 * stub [Context.getSharedPreferences] + [SharedPreferences.Editor] against an
 * in-memory map so we can exercise [FieldTestMode] end-to-end without
 * launching the Android framework.
 */
class FieldTestModeTest {

    private fun fakeContextWithPrefs(): Pair<Context, HashMap<String, Any?>> {
        val store = HashMap<String, Any?>()

        val editor = mock(SharedPreferences.Editor::class.java)
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as Boolean
            editor
        }.`when`(editor).putBoolean(anyString(), anyBoolean())
        doAnswer { }.`when`(editor).apply()
        doAnswer { true }.`when`(editor).commit()

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        doAnswer { inv ->
            val key = inv.arguments[0] as String
            val default = inv.arguments[1] as Boolean
            (store[key] as? Boolean) ?: default
        }.`when`(prefs).getBoolean(anyString(), anyBoolean())

        val ctx = mock(Context::class.java)
        `when`(ctx.getSharedPreferences("tharmesh_field_test", Context.MODE_PRIVATE))
            .thenReturn(prefs)
        return ctx to store
    }

    @Test
    fun `default is disabled`() {
        val (ctx, _) = fakeContextWithPrefs()
        assertFalse(FieldTestMode.isEnabled(ctx))
    }

    @Test
    fun `setEnabled true then false is persisted`() {
        val (ctx, store) = fakeContextWithPrefs()
        FieldTestMode.setEnabled(ctx, true)
        assertTrue(FieldTestMode.isEnabled(ctx))
        assertEquals(true, store["field_test_enabled"])

        FieldTestMode.setEnabled(ctx, false)
        assertFalse(FieldTestMode.isEnabled(ctx))
        assertEquals(false, store["field_test_enabled"])
    }

    @Test
    fun `toggle flips and returns new state`() {
        val (ctx, _) = fakeContextWithPrefs()
        assertTrue(FieldTestMode.toggle(ctx))
        assertTrue(FieldTestMode.isEnabled(ctx))

        assertFalse(FieldTestMode.toggle(ctx))
        assertFalse(FieldTestMode.isEnabled(ctx))
    }
}

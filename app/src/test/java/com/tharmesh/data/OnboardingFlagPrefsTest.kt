package com.tharmesh.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Stage 7 PR C — round-trip test for the onboarding flag and the
 * grandfather logic in [UserPrefs.shouldShowOnboarding]. Mockito-only,
 * mirrors the [ThemeModePrefsTest] pattern (no Robolectric required).
 */
class OnboardingFlagPrefsTest {

    private fun fakeContextWithPrefs(): Pair<Context, HashMap<String, Any?>> {
        val store = HashMap<String, Any?>()

        val editor = mock(SharedPreferences.Editor::class.java)
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as String?
            editor
        }.`when`(editor).putString(anyString(), anyString())
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as Boolean
            editor
        }.`when`(editor).putBoolean(anyString(), anyBoolean())
        doAnswer { }.`when`(editor).apply()

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        doAnswer { inv ->
            store[inv.arguments[0] as String] as String?
        }.`when`(prefs).getString(anyString(), isNull())
        doAnswer { inv ->
            (store[inv.arguments[0] as String] as Boolean?) ?: (inv.arguments[1] as Boolean)
        }.`when`(prefs).getBoolean(anyString(), anyBoolean())

        val ctx = mock(Context::class.java)
        `when`(ctx.getSharedPreferences(eq("tharmesh_user_prefs"), eq(Context.MODE_PRIVATE)))
            .thenReturn(prefs)
        return ctx to store
    }

    @Test
    fun `fresh install with no profile should show onboarding`() {
        val (ctx, _) = fakeContextWithPrefs()
        assertTrue(UserPrefs.shouldShowOnboarding(ctx))
        assertFalse(UserPrefs.isOnboarded(ctx))
    }

    @Test
    fun `markOnboarded persists and shouldShowOnboarding returns false`() {
        val (ctx, store) = fakeContextWithPrefs()
        UserPrefs.markOnboarded(ctx)
        assertTrue(store["onboarded"] as Boolean)
        assertFalse(UserPrefs.shouldShowOnboarding(ctx))
    }

    @Test
    fun `existing install with anonymous default username falls into onboarding`() {
        val (ctx, store) = fakeContextWithPrefs()
        // Pre-PR-C anonymous profiles always have username == userId.
        store["user_id"] = "user-deadbeef"
        store["username"] = "user-deadbeef"
        store["authenticated"] = true
        assertTrue(UserPrefs.shouldShowOnboarding(ctx))
    }

    @Test
    fun `existing install with a real chosen display name is grandfathered`() {
        val (ctx, store) = fakeContextWithPrefs()
        // Google sign-in or a user who edited their profile pre-PR-C.
        store["user_id"] = "tm-abcdef012345"
        store["username"] = "Nohri"
        store["authenticated"] = true
        assertFalse(UserPrefs.shouldShowOnboarding(ctx))
        // Persisted, so the next launch is fast-pathed.
        assertTrue(store["onboarded"] as Boolean)
    }
}

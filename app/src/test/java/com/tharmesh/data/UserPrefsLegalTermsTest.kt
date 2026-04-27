package com.tharmesh.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Stage 8.4 — Pakistan compliance Terms-of-Use gate version codec.
 *
 * Mockito-only (no Robolectric); shares the SharedPreferences fake
 * shape with [OnboardingFlagPrefsTest]. The intent is to lock in:
 *
 *  - a fresh install reports the gate as not yet accepted;
 *  - calling [UserPrefs.markTermsAccepted] persists BOTH the version
 *    code (so a future bump re-prompts) AND the wall-clock timestamp
 *    (so the in-app re-read can show "accepted on …");
 *  - a stored version code older than [LEGAL_TERMS_VERSION_CURRENT]
 *    re-prompts the user; a stored code equal-or-greater does not.
 */
class UserPrefsLegalTermsTest {

    private fun fakeContextWithPrefs(): Pair<Context, HashMap<String, Any?>> {
        val store = HashMap<String, Any?>()

        val editor = mock(SharedPreferences.Editor::class.java)
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as Int
            editor
        }.`when`(editor).putInt(anyString(), anyInt())
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as Long
            editor
        }.`when`(editor).putLong(anyString(), anyLong())
        doAnswer { }.`when`(editor).apply()

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        doAnswer { inv ->
            (store[inv.arguments[0] as String] as Int?) ?: (inv.arguments[1] as Int)
        }.`when`(prefs).getInt(anyString(), anyInt())
        doAnswer { inv ->
            (store[inv.arguments[0] as String] as Long?) ?: (inv.arguments[1] as Long)
        }.`when`(prefs).getLong(anyString(), anyLong())

        val ctx = mock(Context::class.java)
        `when`(ctx.getSharedPreferences(eq("tharmesh_user_prefs"), eq(Context.MODE_PRIVATE)))
            .thenReturn(prefs)
        return ctx to store
    }

    @Test
    fun `fresh install reports terms not accepted`() {
        val (ctx, _) = fakeContextWithPrefs()
        assertFalse(UserPrefs.hasAcceptedCurrentTerms(ctx))
        assertEquals(0L, UserPrefs.termsAcceptedAtMs(ctx))
    }

    @Test
    fun `markTermsAccepted persists version and timestamp`() {
        val (ctx, store) = fakeContextWithPrefs()
        val before = System.currentTimeMillis()
        UserPrefs.markTermsAccepted(ctx)
        val after = System.currentTimeMillis()

        assertTrue(UserPrefs.hasAcceptedCurrentTerms(ctx))
        assertEquals(
            UserPrefs.LEGAL_TERMS_VERSION_CURRENT,
            store["legal_terms_version_accepted"] as Int
        )
        val ts = UserPrefs.termsAcceptedAtMs(ctx)
        assertTrue("timestamp must be wall-clock", ts in before..after)
    }

    @Test
    fun `older accepted version re-prompts user`() {
        val (ctx, store) = fakeContextWithPrefs()
        // Simulate an existing install that accepted version 0 of the
        // Terms (older than the current version). The gate must
        // re-prompt so the user re-affirms the updated text.
        store["legal_terms_version_accepted"] = 0
        store["legal_terms_accepted_at_ms"] = 1L
        assertFalse(UserPrefs.hasAcceptedCurrentTerms(ctx))
    }

    @Test
    fun `current accepted version does not re-prompt`() {
        val (ctx, store) = fakeContextWithPrefs()
        store["legal_terms_version_accepted"] = UserPrefs.LEGAL_TERMS_VERSION_CURRENT
        store["legal_terms_accepted_at_ms"] = 12345L
        assertTrue(UserPrefs.hasAcceptedCurrentTerms(ctx))
        assertEquals(12345L, UserPrefs.termsAcceptedAtMs(ctx))
    }

    @Test
    fun `future accepted version is grandfathered as accepted`() {
        // If a downgrade happens (rolled-back release of the app) we
        // should NOT re-prompt a user who already accepted a NEWER
        // version of the Terms — they already saw stricter text.
        val (ctx, store) = fakeContextWithPrefs()
        store["legal_terms_version_accepted"] = UserPrefs.LEGAL_TERMS_VERSION_CURRENT + 1
        assertTrue(UserPrefs.hasAcceptedCurrentTerms(ctx))
    }
}

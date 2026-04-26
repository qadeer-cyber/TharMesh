package com.tharmesh.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Stage 7 PR E — pure-JVM tests for [GrowthMetrics] using the same
 * Mockito-only fake-prefs pattern as [OnboardingFlagPrefsTest].
 *
 * Locked-down behaviours:
 *  - counters increment monotonically and snapshot reads them back
 *  - one-shot prompt flags fire exactly once
 *  - prompts only latch at the right thresholds (1 chat, 3 contacts)
 */
class GrowthMetricsTest {

    private fun fakeContextWithPrefs(): Pair<Context, HashMap<String, Any?>> {
        val store = HashMap<String, Any?>()

        val editor = mock(SharedPreferences.Editor::class.java)
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as Long
            editor
        }.`when`(editor).putLong(anyString(), anyLong())
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as String?
            editor
        }.`when`(editor).putString(anyString(), anyString())
        doAnswer { }.`when`(editor).apply()

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        doAnswer { inv ->
            (store[inv.arguments[0] as String] as Long?) ?: (inv.arguments[1] as Long)
        }.`when`(prefs).getLong(anyString(), anyLong())
        doAnswer { inv ->
            store[inv.arguments[0] as String] as String?
        }.`when`(prefs).getString(anyString(), isNull())

        val ctx = mock(Context::class.java)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.getSharedPreferences(eq("tharmesh_growth_metrics"), eq(Context.MODE_PRIVATE)))
            .thenReturn(prefs)
        return ctx to store
    }

    @Test
    fun fresh_snapshot_is_all_zero() {
        val (ctx, _) = fakeContextWithPrefs()
        val s = GrowthMetrics.snapshot(ctx)
        assertEquals(0L, s.contactsAdded)
        assertEquals(0L, s.invitesSent)
        assertEquals(0L, s.invitesAccepted)
        assertEquals(0L, s.chatsStarted)
    }

    @Test
    fun counters_increment_monotonically_and_independently() {
        val (ctx, _) = fakeContextWithPrefs()
        GrowthMetrics.recordContactAdded(ctx)
        GrowthMetrics.recordContactAdded(ctx)
        GrowthMetrics.recordInviteSent(ctx)
        GrowthMetrics.recordInviteAccepted(ctx)
        GrowthMetrics.recordInviteAccepted(ctx)
        GrowthMetrics.recordInviteAccepted(ctx)
        GrowthMetrics.recordChatStarted(ctx)

        val s = GrowthMetrics.snapshot(ctx)
        assertEquals(2L, s.contactsAdded)
        assertEquals(1L, s.invitesSent)
        assertEquals(3L, s.invitesAccepted)
        assertEquals(1L, s.chatsStarted)
    }

    @Test
    fun first_chat_prompt_latches_on_first_chat_only_and_consumes_once() {
        val (ctx, store) = fakeContextWithPrefs()
        // No chats yet → nothing to consume.
        assertFalse(GrowthMetrics.consumeFirstChatPrompt(ctx))
        assertNull(store["prompt_first_chat_state"])

        GrowthMetrics.recordChatStarted(ctx)
        assertEquals("pending", store["prompt_first_chat_state"])

        assertTrue(GrowthMetrics.consumeFirstChatPrompt(ctx))
        assertEquals("shown", store["prompt_first_chat_state"])

        // Second consume returns false; further chats don't re-pend it.
        assertFalse(GrowthMetrics.consumeFirstChatPrompt(ctx))
        GrowthMetrics.recordChatStarted(ctx)
        GrowthMetrics.recordChatStarted(ctx)
        assertFalse(GrowthMetrics.consumeFirstChatPrompt(ctx))
        assertEquals("shown", store["prompt_first_chat_state"])
    }

    @Test
    fun three_contacts_prompt_latches_at_third_contact_and_consumes_once() {
        val (ctx, store) = fakeContextWithPrefs()
        GrowthMetrics.recordContactAdded(ctx)
        GrowthMetrics.recordContactAdded(ctx)
        // Below threshold — should not be pending.
        assertNull(store["prompt_three_contacts_state"])
        assertFalse(GrowthMetrics.consumeThreeContactsPrompt(ctx))

        GrowthMetrics.recordContactAdded(ctx)
        assertEquals("pending", store["prompt_three_contacts_state"])
        assertTrue(GrowthMetrics.consumeThreeContactsPrompt(ctx))

        // Subsequent contacts don't re-pend the prompt.
        GrowthMetrics.recordContactAdded(ctx)
        GrowthMetrics.recordContactAdded(ctx)
        assertEquals("shown", store["prompt_three_contacts_state"])
        assertFalse(GrowthMetrics.consumeThreeContactsPrompt(ctx))
    }

    @Test
    fun three_contacts_prompt_skips_pending_state_if_already_consumed() {
        // If the threshold is crossed *and* consumed, we should not
        // overwrite the "shown" state with "pending" on later additions.
        val (ctx, store) = fakeContextWithPrefs()
        repeat(3) { GrowthMetrics.recordContactAdded(ctx) }
        assertTrue(GrowthMetrics.consumeThreeContactsPrompt(ctx))
        repeat(5) { GrowthMetrics.recordContactAdded(ctx) }
        assertEquals("shown", store["prompt_three_contacts_state"])
    }
}

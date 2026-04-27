package com.tharmesh.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Stage 8.4 \u2014 unit tests for the SharedPreferences-backed
 * [BlockedContacts] helper. Mockito-only (no Robolectric); the fake
 * SharedPreferences mirrors the helper's `getStringSet` / `putStringSet`
 * usage so we can verify both the in-memory StateFlow AND the
 * persisted set on disk for every operation.
 */
class BlockedContactsTest {

    private fun fakeContextWithPrefs(): Pair<Context, HashMap<String, Any?>> {
        val store = HashMap<String, Any?>()

        val editor = mock(SharedPreferences.Editor::class.java)
        @Suppress("UNCHECKED_CAST")
        doAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as Set<String>?
            editor
        }.`when`(editor).putStringSet(anyString(), any())
        doAnswer { }.`when`(editor).apply()

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        @Suppress("UNCHECKED_CAST")
        doAnswer { inv ->
            (store[inv.arguments[0] as String] as Set<String>?)
                ?: (inv.arguments[1] as Set<String>?)
        }.`when`(prefs).getStringSet(anyString(), any())

        val ctx = mock(Context::class.java)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.getSharedPreferences(eq("tharmesh_user_prefs"), eq(Context.MODE_PRIVATE)))
            .thenReturn(prefs)
        return ctx to store
    }

    @Before fun resetCache() {
        // Singleton cache lives across the JVM; reset it so each
        // @Test starts with a fresh SharedPreferences.
        BlockedContacts.resetCacheForTest()
    }

    @Test
    fun `fresh install reports no blocked contacts`() {
        val (ctx, _) = fakeContextWithPrefs()
        assertFalse(BlockedContacts.isBlocked(ctx, "user-a"))
        assertTrue(BlockedContacts.snapshot(ctx).isEmpty())
    }

    @Test
    fun `block then isBlocked returns true and persists to prefs`() {
        val (ctx, store) = fakeContextWithPrefs()
        BlockedContacts.block(ctx, "user-a")
        assertTrue(BlockedContacts.isBlocked(ctx, "user-a"))
        @Suppress("UNCHECKED_CAST")
        val persisted = store["legal_blocked_user_ids"] as Set<String>
        assertEquals(setOf("user-a"), persisted)
    }

    @Test
    fun `unblock then isBlocked returns false`() {
        val (ctx, _) = fakeContextWithPrefs()
        BlockedContacts.block(ctx, "user-a")
        BlockedContacts.unblock(ctx, "user-a")
        assertFalse(BlockedContacts.isBlocked(ctx, "user-a"))
        assertTrue(BlockedContacts.snapshot(ctx).isEmpty())
    }

    @Test
    fun `block is idempotent`() {
        val (ctx, _) = fakeContextWithPrefs()
        BlockedContacts.block(ctx, "user-a")
        BlockedContacts.block(ctx, "user-a")
        BlockedContacts.block(ctx, "user-a")
        assertEquals(listOf("user-a"), BlockedContacts.snapshot(ctx))
    }

    @Test
    fun `unblock of unblocked userId is a no-op`() {
        val (ctx, _) = fakeContextWithPrefs()
        BlockedContacts.unblock(ctx, "user-a")
        BlockedContacts.unblock(ctx, "user-b")
        assertTrue(BlockedContacts.snapshot(ctx).isEmpty())
    }

    @Test
    fun `blank userId is never blocked and block ignores blank input`() {
        val (ctx, store) = fakeContextWithPrefs()
        BlockedContacts.block(ctx, "")
        BlockedContacts.block(ctx, "   ")
        assertFalse(BlockedContacts.isBlocked(ctx, ""))
        assertFalse(BlockedContacts.isBlocked(ctx, "   "))
        // Nothing should have been persisted.
        assertTrue(store["legal_blocked_user_ids"] == null ||
            (store["legal_blocked_user_ids"] as Set<*>).isEmpty())
    }

    @Test
    fun `snapshot is sorted for deterministic UI`() {
        val (ctx, _) = fakeContextWithPrefs()
        BlockedContacts.block(ctx, "z-user")
        BlockedContacts.block(ctx, "a-user")
        BlockedContacts.block(ctx, "m-user")
        assertEquals(listOf("a-user", "m-user", "z-user"), BlockedContacts.snapshot(ctx))
    }

    @Test
    fun `multiple block-unblock cycles converge to expected state`() {
        val (ctx, _) = fakeContextWithPrefs()
        BlockedContacts.block(ctx, "user-a")
        BlockedContacts.block(ctx, "user-b")
        BlockedContacts.block(ctx, "user-c")
        BlockedContacts.unblock(ctx, "user-b")
        assertTrue(BlockedContacts.isBlocked(ctx, "user-a"))
        assertFalse(BlockedContacts.isBlocked(ctx, "user-b"))
        assertTrue(BlockedContacts.isBlocked(ctx, "user-c"))
        assertEquals(listOf("user-a", "user-c"), BlockedContacts.snapshot(ctx))
    }
}

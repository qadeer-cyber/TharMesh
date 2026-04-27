// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stage 8.3 — lightweight blocked-contacts list backed by
 * [android.content.SharedPreferences]. Keyed by the peer's stable
 * userId (the same identity that flows through
 * [com.tharmesh.data.MessageRepository.addContact] and the
 * `srcId` of every inbound [com.tharmesh.dtn.MeshBundle]), so a single
 * lookup covers every add path (QR scan, manual invite, nearby pick,
 * NewChatSheet) and every receive path.
 *
 * Stored as a `Set<String>` in the same `tharmesh_user_prefs` file
 * [UserPrefs] uses, under the key [KEY_BLOCKED_USER_IDS]. We deliberately
 * avoid a Room schema change here — the spec calls for a "lightweight"
 * abuse-mitigation layer, and SharedPreferences is durable across
 * process death + reinstalls of the same APK signing key, which is all
 * the persistence the spec requires.
 *
 * The block list is exposed both as an immediate snapshot ([snapshot])
 * for synchronous decisions inside the receive loop, and as a
 * [StateFlow] ([observe]) for any UI that wants to repaint when the
 * list changes (e.g. the Settings → Privacy → Blocked Contacts screen
 * and the contact profile's Block / Unblock toggle).
 */
object BlockedContacts {

    private const val PREFS_NAME = "tharmesh_user_prefs"
    private const val KEY_BLOCKED_USER_IDS = "legal_blocked_user_ids"

    /**
     * Process-wide cache of the blocked set. Read once on first access
     * (lazy) and then mutated only via [block] / [unblock]. Keeping a
     * cached snapshot lets [isBlocked] resolve in O(1) without a
     * SharedPreferences read on the hot path of
     * [com.tharmesh.data.MessageRepository.handleIncomingBundle].
     *
     * Wrapped in a [MutableStateFlow] so the UI can subscribe.
     */
    private val state: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    private var loaded: Boolean = false

    /**
     * Clear the in-memory cache. Test-only \u2014 lets a unit test mock a
     * fresh SharedPreferences each run without the previous run's
     * cached set leaking through. Production code must never call this
     * (the live block list lives in the cache + SharedPreferences).
     */
    @androidx.annotation.VisibleForTesting
    @JvmStatic
    fun resetCacheForTest() {
        synchronized(this) {
            state.value = emptySet()
            loaded = false
        }
    }

    /**
     * Force-load the persisted list into the in-memory cache. Idempotent
     * and called on first access by every public function below.
     */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getStringSet(KEY_BLOCKED_USER_IDS, emptySet()) ?: emptySet()
            state.value = raw.filter { it.isNotBlank() }.toSet()
            loaded = true
        }
    }

    /**
     * @return `true` if [userId] is currently in the blocked set.
     * Returns `false` for blank input — a blank userId is never a valid
     * peer identity and we don't want to accidentally drop a malformed
     * but legitimate bundle.
     */
    @JvmStatic
    fun isBlocked(context: Context, userId: String): Boolean {
        if (userId.isBlank()) return false
        ensureLoaded(context)
        return state.value.contains(userId)
    }

    /** Snapshot of the currently-blocked set, sorted for deterministic UI. */
    @JvmStatic
    fun snapshot(context: Context): List<String> {
        ensureLoaded(context)
        return state.value.sorted()
    }

    /**
     * StateFlow over the blocked-userId set. Each emission is a fresh,
     * immutable snapshot — observers must not mutate it. Useful for the
     * Blocked Contacts settings screen which reactively repaints
     * whenever the user blocks / unblocks someone.
     */
    @JvmStatic
    fun observe(context: Context): StateFlow<Set<String>> {
        ensureLoaded(context)
        return state
    }

    /**
     * Add [userId] to the block set. Idempotent — blocking an already
     * blocked userId is a no-op. The operation is intentionally
     * synchronous + commit()-free (uses apply()), matching the rest of
     * UserPrefs' write semantics; a process kill mid-write at most loses
     * the most recent block, which the user can reapply.
     */
    @JvmStatic
    fun block(context: Context, userId: String) {
        if (userId.isBlank()) return
        ensureLoaded(context)
        synchronized(this) {
            val current = state.value
            if (current.contains(userId)) return
            val next = current + userId
            persist(context, next)
            state.value = next
        }
    }

    /** Remove [userId] from the block set. Idempotent. */
    @JvmStatic
    fun unblock(context: Context, userId: String) {
        if (userId.isBlank()) return
        ensureLoaded(context)
        synchronized(this) {
            val current = state.value
            if (!current.contains(userId)) return
            val next = current - userId
            persist(context, next)
            state.value = next
        }
    }

    private fun persist(context: Context, set: Set<String>) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_BLOCKED_USER_IDS, set)
            .apply()
    }

    /**
     * Test-only: drop both the in-memory cache and the persisted set.
     * Production code never needs this — the cache lives for the life
     * of the process and the persisted set is the source of truth.
     */
    @androidx.annotation.VisibleForTesting
    @JvmStatic
    fun resetForTests(context: Context) {
        synchronized(this) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_BLOCKED_USER_IDS).apply()
            state.value = emptySet()
            loaded = false
        }
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.data

import android.content.Context

/**
 * Stage 7 PR E — local-only growth-engine counters.
 *
 * Backed by [Context.MODE_PRIVATE] [SharedPreferences] (in a
 * dedicated file so the user's `tharmesh_user_prefs` profile keys
 * stay clean). Per Stage 7 spec / TharMesh's offline-first stance,
 * **nothing leaves the device** — these counters power the
 * Diagnostics screen and the in-app viral prompts and nothing else.
 *
 * Tracked counters:
 *
 *  - `contacts_added`       — bumped from [MessageRepository.addContact].
 *  - `invites_sent`         — bumped from `MyQrActivity` each time the
 *                             user displays their invite QR.
 *  - `invites_accepted`     — bumped from each QR-scan-and-added flow
 *                             (NewChatSheet, ContactsFragment,
 *                             OnboardingActivity, nearby banner).
 *  - `chats_started`        — bumped on the first outgoing message
 *                             ever sent to a peer (per peer, not per
 *                             session).
 *
 * Two consume-once flags drive the in-app viral prompts:
 *
 *  - `prompt_after_first_chat`     — set when `chats_started` hits 1.
 *  - `prompt_after_three_contacts` — set when `contacts_added` hits 3.
 *
 * Both [consumeFirstChatPrompt] / [consumeThreeContactsPrompt] return
 * `true` exactly once and leave the latched flag in a "consumed"
 * state so the UI never re-prompts.
 */
object GrowthMetrics {

    private const val PREFS = "tharmesh_growth_metrics"

    private const val KEY_CONTACTS_ADDED = "contacts_added"
    private const val KEY_INVITES_SENT = "invites_sent"
    private const val KEY_INVITES_ACCEPTED = "invites_accepted"
    private const val KEY_CHATS_STARTED = "chats_started"

    private const val KEY_PROMPT_FIRST_CHAT = "prompt_first_chat_state"
    private const val KEY_PROMPT_THREE_CONTACTS = "prompt_three_contacts_state"

    /** Tri-state lifecycle for a one-shot prompt. */
    private const val STATE_PENDING = "pending"
    private const val STATE_SHOWN = "shown"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bump(context: Context, key: String): Long {
        val p = prefs(context)
        val next = p.getLong(key, 0L) + 1L
        p.edit().putLong(key, next).apply()
        return next
    }

    fun recordContactAdded(context: Context) {
        val total = bump(context, KEY_CONTACTS_ADDED)
        if (total >= 3L && prefs(context).getString(KEY_PROMPT_THREE_CONTACTS, null) == null) {
            prefs(context).edit().putString(KEY_PROMPT_THREE_CONTACTS, STATE_PENDING).apply()
        }
    }

    fun recordInviteSent(context: Context) {
        bump(context, KEY_INVITES_SENT)
    }

    fun recordInviteAccepted(context: Context) {
        bump(context, KEY_INVITES_ACCEPTED)
    }

    fun recordChatStarted(context: Context) {
        val total = bump(context, KEY_CHATS_STARTED)
        if (total == 1L && prefs(context).getString(KEY_PROMPT_FIRST_CHAT, null) == null) {
            prefs(context).edit().putString(KEY_PROMPT_FIRST_CHAT, STATE_PENDING).apply()
        }
    }

    /** Read-only counter snapshot for Diagnostics. */
    data class Snapshot(
        val contactsAdded: Long,
        val invitesSent: Long,
        val invitesAccepted: Long,
        val chatsStarted: Long
    )

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            contactsAdded = p.getLong(KEY_CONTACTS_ADDED, 0L),
            invitesSent = p.getLong(KEY_INVITES_SENT, 0L),
            invitesAccepted = p.getLong(KEY_INVITES_ACCEPTED, 0L),
            chatsStarted = p.getLong(KEY_CHATS_STARTED, 0L)
        )
    }

    /**
     * Returns `true` exactly once after the user has sent their first
     * message ever. Subsequent calls return `false` (the prompt has
     * been "consumed" — we don't re-prompt).
     */
    fun consumeFirstChatPrompt(context: Context): Boolean = consumeOneShot(context, KEY_PROMPT_FIRST_CHAT)

    /**
     * Returns `true` exactly once after the user's contact list reaches
     * 3 entries. Subsequent calls return `false`.
     */
    fun consumeThreeContactsPrompt(context: Context): Boolean = consumeOneShot(context, KEY_PROMPT_THREE_CONTACTS)

    private fun consumeOneShot(context: Context, key: String): Boolean {
        val p = prefs(context)
        if (p.getString(key, null) != STATE_PENDING) return false
        p.edit().putString(key, STATE_SHOWN).apply()
        return true
    }
}

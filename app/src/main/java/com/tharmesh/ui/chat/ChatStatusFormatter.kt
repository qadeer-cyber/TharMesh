// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.chat

import com.tharmesh.db.MessageStatus

/**
 * Stage 8.0 — pure helper that turns the chat header subtitle from a static
 * "Online · via Mesh" label into a real, state-aware sentence. Used by
 * [ChatActivity.updateTopStatus] and exercised directly by
 * `ChatStatusFormatterTest` without needing Robolectric, a Room database,
 * or the Android framework.
 *
 * The header subtitle is the single most important UX surface in the chat
 * screen: it's where the user looks to answer "is my message going to
 * arrive?". Pre-7.9 the answer was hard-coded to "Online · via Mesh"
 * regardless of the real mesh state, and a QUEUED message rendered as
 * "Online · via Mesh · ⏳" — literally telling the user they were online
 * while their message was stuck offline. This formatter fixes that.
 *
 * Two orthogonal axes feed into the subtitle:
 *
 *  - **Mesh state** — derived from the same
 *    [com.tharmesh.permissions.PermissionMonitor.snapshot] +
 *    `directory.nodes` data the chat-list mesh-warning-dot uses. Lifted
 *    here as the [MeshState] sealed class so the formatter is decoupled
 *    from Android Context / the directory implementation.
 *
 *  - **Last-message status** — the [com.tharmesh.db.MessageStatus] string
 *    of the most recent message the local user sent in this chat (or
 *    `null` if the user hasn't sent anything yet, in which case only
 *    [MeshState] is rendered).
 *
 * Per-message status takes precedence over mesh state when it carries
 * actionable information ("Failed — tap to retry") OR when it would
 * otherwise contradict the mesh subtitle ("Queued — will send when
 * connected" instead of "Online · 3 nearby"). Sent / Delivered / Read
 * are presented alongside the mesh state because the user wants both
 * "my message landed" AND "I'm still online" at a glance.
 */
internal object ChatStatusFormatter {

    /**
     * Decoupled view of mesh state. Mirrors the four meaningful cases the
     * mesh-warning-dot already renders, plus the count of currently-online
     * peers when permissions are healthy.
     */
    sealed class MeshState {
        /** Permissions granted, BT + Location on, but no nearby peers in range yet. */
        object Searching : MeshState()
        /**
         * Permissions granted, BT + Location on, [nearbyOnline] peers currently
         * connected. Callers are expected to emit [Searching] instead of
         * `Online(0)` — the formatter defensively maps `nearbyOnline <= 0` to
         * the searching string so a buggy caller can never produce a
         * misleading "Online · 0 devices nearby" subtitle.
         */
        data class Online(val nearbyOnline: Int) : MeshState()
        /** Bluetooth or Location is off, or runtime perms denied — mesh cannot deliver. */
        object Offline : MeshState()
    }

    /**
     * String resource bundle the formatter needs. Passed in instead of read
     * from `Context.getString` so the formatter stays Android-framework-free
     * and unit-testable. [ChatActivity] supplies real string resources via
     * [Strings.fromContext].
     */
    data class Strings(
        val online1Fmt: String,         // "Online · 1 device nearby"
        val onlineNFmt: String,         // "Online · %1$d devices nearby"
        val searching: String,          // "Searching for nearby devices…"
        val offline: String,            // "Offline · messages will send when connected"
        val msgQueued: String,          // "Queued — will send when connected"
        val msgSending: String,         // "Sending…"
        val msgSent: String,            // "Sent"
        val msgDelivered: String,       // "Delivered"
        val msgRead: String,            // "Read"
        val msgFailed: String,          // "Failed — tap to retry"
        val separator: String = " · "
    )

    /**
     * Produces the subtitle string for the chat header.
     *
     * Precedence rules (UX-driven; tested in ChatStatusFormatterTest):
     *
     *  1. If the user's last message is FAILED → return "Failed — tap to
     *     retry" alone. Do NOT decorate with mesh state — failure is
     *     actionable and must dominate.
     *  2. If the last message is QUEUED OR SENDING → return "Queued — will
     *     send when connected" (or "Sending…" for SENDING when the mesh
     *     is online). The user must not see "Online · …" while their
     *     message is stuck.
     *  3. Otherwise — last message is SENT / DELIVERED / READ / null —
     *     render the mesh state followed by the per-message tick if any.
     *
     * Examples:
     *
     *  | mesh state    | last msg     | subtitle                                             |
     *  |---------------|--------------|------------------------------------------------------|
     *  | Online(3)     | null         | "Online · 3 devices nearby"                          |
     *  | Online(1)     | DELIVERED    | "Online · 1 device nearby · Delivered"               |
     *  | Searching     | null         | "Searching for nearby devices…"                      |
     *  | Searching     | QUEUED       | "Queued — will send when connected"                  |
     *  | Offline       | null         | "Offline · messages will send when connected"        |
     *  | Offline       | FAILED       | "Failed — tap to retry"                              |
     *  | Online(2)     | FAILED       | "Failed — tap to retry"                              |
     */
    fun format(
        mesh: MeshState,
        lastOutgoingStatus: String?,
        strings: Strings
    ): String {
        // Rule 1 — FAILED dominates regardless of mesh state.
        if (lastOutgoingStatus == MessageStatus.FAILED) return strings.msgFailed
        // Rule 2 — QUEUED / SENDING contradicts mesh state, so it must
        // dominate too. SENDING uses a slightly more "in motion" wording
        // because by definition the transport has accepted the bundle.
        if (lastOutgoingStatus == MessageStatus.QUEUED) return strings.msgQueued
        if (lastOutgoingStatus == MessageStatus.SENDING) return strings.msgSending

        // Rule 3 — render mesh state, optionally suffixed with a per-tick.
        val meshText = when (mesh) {
            is MeshState.Searching -> strings.searching
            is MeshState.Offline -> strings.offline
            is MeshState.Online -> when {
                // Defensive: a count of 0 (or anything negative) is
                // semantically "no peers connected", which is exactly
                // what Searching means. Falling back to the searching
                // string keeps the formatter's contract truthful even
                // if a future caller forgets the Online(0) → Searching
                // guard ChatActivity.currentMeshState() already does.
                mesh.nearbyOnline <= 0 -> strings.searching
                mesh.nearbyOnline == 1 -> strings.online1Fmt
                else -> String.format(strings.onlineNFmt, mesh.nearbyOnline)
            }
        }
        val tick = when (lastOutgoingStatus) {
            MessageStatus.SENT -> strings.msgSent
            MessageStatus.DELIVERED -> strings.msgDelivered
            MessageStatus.READ -> strings.msgRead
            else -> null
        }
        return if (tick != null) meshText + strings.separator + tick else meshText
    }
}

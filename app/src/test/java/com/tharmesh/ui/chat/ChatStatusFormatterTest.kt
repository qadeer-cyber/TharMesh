// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.chat

import com.tharmesh.db.MessageStatus
import com.tharmesh.ui.chat.ChatStatusFormatter.MeshState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 8.0 — pure-JVM tests for [ChatStatusFormatter]. The formatter is
 * a no-Android-Context helper, so these run in the standard
 * `testDebugUnitTest` task without Robolectric or any platform dependency.
 *
 * Coverage matrix (subset of the precedence rules documented on
 * [ChatStatusFormatter.format]):
 *
 *  - Online + null            → "Online · 1 device nearby" / "… N nearby"
 *  - Online + DELIVERED       → mesh state · "Delivered"
 *  - Searching + null         → "Searching for nearby devices…"
 *  - Searching + QUEUED       → "Queued — will send when connected"
 *  - Offline   + null         → offline string
 *  - Offline   + FAILED       → "Failed — tap to retry" (FAILED dominates)
 *  - Online    + FAILED       → "Failed — tap to retry" (FAILED dominates)
 *  - Online    + SENDING      → "Sending…" (in-flight dominates)
 */
class ChatStatusFormatterTest {

    private val s = ChatStatusFormatter.Strings(
        online1Fmt = "Online · 1 device nearby",
        onlineNFmt = "Online · %1\$d devices nearby",
        searching = "Searching for nearby devices…",
        offline = "Offline · messages will send when connected",
        msgQueued = "Queued — will send when connected",
        msgSending = "Sending…",
        msgSent = "Sent",
        msgDelivered = "Delivered",
        msgRead = "Read",
        msgFailed = "Failed — tap to retry"
    )

    @Test
    fun rendersOnlineWithSingularLabel_whenExactlyOnePeer() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 1),
            lastOutgoingStatus = null,
            strings = s
        )
        assertEquals("Online · 1 device nearby", out)
    }

    @Test
    fun rendersOnlineWithPluralCount_whenMultiplePeers() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 3),
            lastOutgoingStatus = null,
            strings = s
        )
        assertEquals("Online · 3 devices nearby", out)
    }

    @Test
    fun rendersSearching_whenPermsReadyButNoOnlinePeers() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Searching,
            lastOutgoingStatus = null,
            strings = s
        )
        assertEquals("Searching for nearby devices…", out)
    }

    @Test
    fun rendersOffline_whenPermsMissing() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Offline,
            lastOutgoingStatus = null,
            strings = s
        )
        assertEquals("Offline · messages will send when connected", out)
    }

    @Test
    fun queuedDominatesOverOnlineMeshState() {
        // Pre-8.0 the chat header rendered "Online · via Mesh · ⏳" while a
        // message was QUEUED, which lied about the user's effective state.
        // The new rule is unambiguous: queued => "Queued — will send when
        // connected", regardless of how the mesh actually looks.
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 5),
            lastOutgoingStatus = MessageStatus.QUEUED,
            strings = s
        )
        assertEquals("Queued — will send when connected", out)
    }

    @Test
    fun sendingDominatesOverOnlineMeshState() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 2),
            lastOutgoingStatus = MessageStatus.SENDING,
            strings = s
        )
        assertEquals("Sending…", out)
    }

    @Test
    fun failedDominatesEverythingElse_includingOnline() {
        // FAILED is actionable ("tap to retry") and must not be hidden
        // behind the mesh subtitle — the user needs to see it immediately.
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 4),
            lastOutgoingStatus = MessageStatus.FAILED,
            strings = s
        )
        assertEquals("Failed — tap to retry", out)
    }

    @Test
    fun failedDominatesOverOfflineMeshState() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Offline,
            lastOutgoingStatus = MessageStatus.FAILED,
            strings = s
        )
        assertEquals("Failed — tap to retry", out)
    }

    @Test
    fun deliveredAppendsTickToMeshState_notReplaces() {
        // Sent / Delivered / Read are NOT in conflict with mesh state — the
        // user wants both "I'm online" and "my last message landed". The
        // formatter concatenates them with " · ".
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 2),
            lastOutgoingStatus = MessageStatus.DELIVERED,
            strings = s
        )
        assertEquals("Online · 2 devices nearby · Delivered", out)
    }

    @Test
    fun sentTickRendersWithMeshState() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 1),
            lastOutgoingStatus = MessageStatus.SENT,
            strings = s
        )
        assertEquals("Online · 1 device nearby · Sent", out)
    }

    @Test
    fun readTickRendersWithMeshState() {
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 4),
            lastOutgoingStatus = MessageStatus.READ,
            strings = s
        )
        assertEquals("Online · 4 devices nearby · Read", out)
    }

    @Test
    fun zeroPeersFallsBackToSingularPath_notNegativeFormat() {
        // Defensive: an empty-list count of 0 should never reach the
        // formatter (caller would emit Searching), but if it does, the
        // singular fallback prevents any "Online · 0 devices nearby"
        // from leaking through. This is the same guard the production
        // mesh-warning code uses.
        val out = ChatStatusFormatter.format(
            mesh = MeshState.Online(nearbyOnline = 0),
            lastOutgoingStatus = null,
            strings = s
        )
        assertEquals("Online · 1 device nearby", out)
    }
}

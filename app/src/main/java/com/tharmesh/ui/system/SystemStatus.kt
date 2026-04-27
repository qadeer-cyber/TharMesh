// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.system

/**
 * Stage 9.2 — single source of truth for the "what is the mesh doing right
 * now?" perception layer. Every visible status surface in the app —
 * brand-header dot, chat list per-row dot, chat header subtitle, alerts
 * top bar, relay top bar, signal bars — is driven by the same enum so
 * the user sees one consistent answer to that question.
 *
 * The enum is intentionally tiny (5 states) and entirely derived: it
 * never owns state of its own. Callers feed in `(permsReady, peerCount,
 * isReachable)` and the resolver picks the matching state.
 *
 * Why a new file rather than reusing [com.tharmesh.ui.chat.ChatStatusFormatter.MeshState]?
 * That helper is chat-screen-specific (its precedence rules pair mesh
 * state with last-message status). The brand header, signal bars, etc.
 * have no per-message context — they just need a top-level "mesh
 * state" enum. Both wrappers are kept; this one is the simpler primary
 * source while ChatStatusFormatter remains the chat-screen specialised
 * formatter that layers per-message status on top.
 */
enum class SystemStatus {
    /**
     * Permissions granted, transport is active, but no peers are in range yet.
     * Visual: cyan pulsing dot / animated wave.
     */
    Searching,

    /**
     * Transport is in the middle of bringing radios up — Bluetooth turning on,
     * Location starting, scan negotiating. This is a transient state we expect
     * to leave within a few seconds; if we're still here after 8s we fall back
     * to [Searching] or [Offline] depending on perms.
     * Visual: cyan pulsing dot, slightly faster cadence than Searching.
     */
    Connecting,

    /**
     * Permissions granted, transport active, at least one peer connected.
     * Visual: solid green stable glow (no animation).
     */
    Connected,

    /**
     * Permissions denied, Bluetooth off, Location off, or transport explicitly
     * stopped. The mesh cannot deliver right now.
     * Visual: dim grey dot, no animation.
     */
    Offline,

    /**
     * Connected to peers but mesh is reporting issues — messages stuck in
     * queue beyond the soft-timeout, or known relay drops above threshold.
     * Visual: amber dot, slow pulse.
     */
    Degraded;

    companion object {
        /**
         * Compute the [SystemStatus] from primitive inputs that any caller
         * already has. Pure function, no Android Context, no globals — fully
         * unit-testable.
         *
         * @param permsReady whether [com.tharmesh.permissions.PermissionMonitor]
         *   reports all required runtime perms granted (Bluetooth + Location).
         * @param transportRunning whether [com.tharmesh.TharMeshApp.ensureMeshStarted]
         *   has been called and the transport hasn't been stopped.
         * @param peerCount number of currently-connected peers from the
         *   directory snapshot (`directory.nodes.value.count { it.online }`).
         * @param connecting whether the transport is still negotiating —
         *   typically true for ~2-4s after [transportRunning] flips on.
         * @param degraded whether the caller has detected a degraded
         *   condition (stuck queue, high drop rate). When true and connected,
         *   we report [Degraded] instead of [Connected].
         */
        @JvmStatic
        fun resolve(
            permsReady: Boolean,
            transportRunning: Boolean,
            peerCount: Int,
            connecting: Boolean = false,
            degraded: Boolean = false
        ): SystemStatus {
            // Hard-fail to Offline first: if we don't have perms or the
            // transport isn't running there is no scenario where any of the
            // other states is honest. This is the same fallback ChatStatusFormatter
            // applies (see [ChatStatusFormatter.MeshState.Offline]).
            if (!permsReady || !transportRunning) return Offline
            // Connecting only "wins" when we have no peers yet — once we've
            // discovered someone, Connected/Degraded is the truer state.
            if (peerCount <= 0 && connecting) return Connecting
            if (peerCount <= 0) return Searching
            if (degraded) return Degraded
            return Connected
        }
    }
}

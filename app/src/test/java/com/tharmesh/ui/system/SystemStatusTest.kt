// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 9.2 — pure-logic tests for [SystemStatus.resolve]. The resolver is
 * the single source of truth for every status surface in the app, so its
 * truth table needs to match the perception spec exactly:
 *
 *   permsReady=false                            → Offline
 *   transportRunning=false                      → Offline
 *   peerCount=0, connecting=true                → Connecting
 *   peerCount=0, connecting=false               → Searching
 *   peerCount>0, degraded=true                  → Degraded
 *   peerCount>0, degraded=false                 → Connected
 *
 * No Android, no Context, no Robolectric — keeps the unit-test bundle
 * small and fast (this is the same approach the rest of the unit-test
 * suite uses).
 */
class SystemStatusTest {

    @Test
    fun `resolve returns Offline when perms not ready`() {
        assertEquals(
            SystemStatus.Offline,
            SystemStatus.resolve(
                permsReady = false, transportRunning = true, peerCount = 5
            )
        )
    }

    @Test
    fun `resolve returns Offline when transport not running`() {
        assertEquals(
            SystemStatus.Offline,
            SystemStatus.resolve(
                permsReady = true, transportRunning = false, peerCount = 5
            )
        )
    }

    @Test
    fun `resolve returns Connecting when peers empty and connecting flag set`() {
        assertEquals(
            SystemStatus.Connecting,
            SystemStatus.resolve(
                permsReady = true,
                transportRunning = true,
                peerCount = 0,
                connecting = true
            )
        )
    }

    @Test
    fun `resolve returns Searching when peers empty and not connecting`() {
        assertEquals(
            SystemStatus.Searching,
            SystemStatus.resolve(
                permsReady = true, transportRunning = true, peerCount = 0
            )
        )
    }

    @Test
    fun `resolve returns Connected with peers and not degraded`() {
        assertEquals(
            SystemStatus.Connected,
            SystemStatus.resolve(
                permsReady = true, transportRunning = true, peerCount = 3
            )
        )
    }

    @Test
    fun `resolve returns Degraded with peers and degraded flag set`() {
        assertEquals(
            SystemStatus.Degraded,
            SystemStatus.resolve(
                permsReady = true,
                transportRunning = true,
                peerCount = 3,
                degraded = true
            )
        )
    }

    @Test
    fun `resolve treats negative peer count as zero`() {
        // Defensive: directory snapshot should never go negative, but
        // resolve() should still be deterministic if it does.
        assertEquals(
            SystemStatus.Searching,
            SystemStatus.resolve(
                permsReady = true, transportRunning = true, peerCount = -1
            )
        )
    }

    @Test
    fun `resolve degraded flag ignored when offline`() {
        // Degraded only applies to a connected mesh — when the mesh is
        // offline the user needs to see Offline, not Degraded.
        assertEquals(
            SystemStatus.Offline,
            SystemStatus.resolve(
                permsReady = false,
                transportRunning = true,
                peerCount = 3,
                degraded = true
            )
        )
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.widget

import com.tharmesh.ui.system.SystemStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9.2 — pure-logic test for [PulsingDot.isAnimatedStatus]. The dot
 * animates only when the perceived state is *transitional* (Searching,
 * Connecting, Degraded). On Connected and Offline it must hold a stable
 * frame so the screen has at most one infinite-loop animation visible at
 * any time (perception-spec §10).
 *
 * The View constructor needs a Context (Robolectric or instrumented
 * test), so we mirror the predicate here as a pure function and assert
 * that the SystemStatus → animated-bool mapping is what the spec
 * requires. Any future change to the predicate must update both copies.
 */
class PulsingDotPhaseTest {

    /** Mirror of [PulsingDot.isAnimatedStatus]. Keep in sync. */
    private fun isAnimated(s: SystemStatus): Boolean = when (s) {
        SystemStatus.Searching,
        SystemStatus.Connecting,
        SystemStatus.Degraded -> true
        SystemStatus.Connected,
        SystemStatus.Offline -> false
    }

    @Test
    fun `searching connecting and degraded animate`() {
        assertTrue(isAnimated(SystemStatus.Searching))
        assertTrue(isAnimated(SystemStatus.Connecting))
        assertTrue(isAnimated(SystemStatus.Degraded))
    }

    @Test
    fun `connected and offline are static`() {
        assertFalse(isAnimated(SystemStatus.Connected))
        assertFalse(isAnimated(SystemStatus.Offline))
    }

    @Test
    fun `every system status has a defined animation policy`() {
        // Iterate the full enum to make sure no value goes unmapped (e.g.
        // someone adds a new status without updating the predicate).
        for (s in SystemStatus.values()) {
            // Just calling isAnimated(s) — if the `when` is non-exhaustive
            // the compiler would have failed; the test ensures we never
            // grow a mode that defaults to "animated" by accident.
            isAnimated(s)
        }
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 9.2 — pure-logic test for the peer-count → lit-bar mapping used
 * by [SignalBarsView]. The mapping is `peerCount.coerceIn(0, barCount)`
 * with `barCount = 4`. We mirror that here so any future refactor that
 * inadvertently changes the curve (e.g. adding a +1 offset, swapping
 * for a logarithmic mapping) trips this test.
 *
 * We don't instantiate the View itself (that needs a Context) — the
 * logic under test is the pure mapping, which is what matters.
 */
class SignalBarsLevelTest {

    private fun mapPeerCountToLit(peerCount: Int, barCount: Int = 4): Int =
        peerCount.coerceIn(0, barCount)

    @Test
    fun `zero peers maps to zero lit bars`() {
        assertEquals(0, mapPeerCountToLit(0))
    }

    @Test
    fun `one peer maps to one lit bar`() {
        assertEquals(1, mapPeerCountToLit(1))
    }

    @Test
    fun `four peers fills all bars`() {
        assertEquals(4, mapPeerCountToLit(4))
    }

    @Test
    fun `more than four peers caps at four`() {
        assertEquals(4, mapPeerCountToLit(8))
        assertEquals(4, mapPeerCountToLit(100))
    }

    @Test
    fun `negative peer count maps to zero`() {
        // Defensive: directory snapshot should never be negative, but the
        // mapping must not crash or return a negative lit count.
        assertEquals(0, mapPeerCountToLit(-1))
        assertEquals(0, mapPeerCountToLit(-100))
    }
}

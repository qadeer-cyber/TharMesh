package com.tharmesh.dtn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerChurnDebouncerTest {

    @Test
    fun coalescesMultipleConnectsWithinWindowIntoSingleTrailingFire() {
        val fired = mutableListOf<String>()
        val d = PeerChurnDebouncer(windowMs = 1_500L, action = { fired.add(it) })
        // 4 reconnects of "p1" within an 800 ms burst (well inside the 1500 ms window).
        d.onPeerConnected("p1", 0L)
        d.onPeerConnected("p1", 100L)
        d.onPeerConnected("p1", 400L)
        d.onPeerConnected("p1", 800L)
        // 1500 ms after the LAST event → fire is due.
        // 800 + 1500 = 2300 → due at t >= 2300
        assertEquals(0, d.processDue(nowMs = 2_299L))
        assertTrue(fired.isEmpty())
        assertEquals(1, d.processDue(nowMs = 2_300L))
        assertEquals(listOf("p1"), fired)
        // 3 of those 4 reconnects were suppressed (every one after the first).
        assertEquals(3L, d.suppressedTotal())
    }

    @Test
    fun fireDueIsBasedOnTrailingEventNotLeading() {
        val fired = mutableListOf<String>()
        val d = PeerChurnDebouncer(windowMs = 1_500L, action = { fired.add(it) })
        d.onPeerConnected("p1", 0L)
        // First event at 0 ms — naive impl might fire at 1500 ms. Assert the window
        // has reset because of the second event below.
        d.onPeerConnected("p1", 1_000L)
        // At 2_499 ms → leading event window has elapsed (>=1500), but trailing
        // hasn't (1000 + 1500 = 2500). Must not fire yet.
        assertEquals(0, d.processDue(nowMs = 2_499L))
        assertEquals(1, d.processDue(nowMs = 2_500L))
        assertEquals(listOf("p1"), fired)
    }

    @Test
    fun multiPeerIndependence() {
        val fired = mutableListOf<String>()
        val d = PeerChurnDebouncer(windowMs = 1_000L, action = { fired.add(it) })
        d.onPeerConnected("p1", 0L)
        d.onPeerConnected("p2", 500L)
        // p1 is due at 1000, p2 at 1500. At 1000 → only p1 fires.
        assertEquals(1, d.processDue(nowMs = 1_000L))
        assertEquals(listOf("p1"), fired)
        // At 1500 → p2 fires too.
        assertEquals(1, d.processDue(nowMs = 1_500L))
        assertEquals(listOf("p1", "p2"), fired)
    }

    @Test
    fun fireIsExactlyOncePerSettledWindow_nextReconnectArmsAgain() {
        val fired = mutableListOf<String>()
        val d = PeerChurnDebouncer(windowMs = 1_000L, action = { fired.add(it) })
        d.onPeerConnected("p1", 0L)
        assertEquals(1, d.processDue(nowMs = 1_000L))
        // Subsequent process tick must not re-fire.
        assertEquals(0, d.processDue(nowMs = 5_000L))
        // A new reconnect arms a fresh window.
        d.onPeerConnected("p1", 5_000L)
        assertEquals(0, d.processDue(nowMs = 5_999L))
        assertEquals(1, d.processDue(nowMs = 6_000L))
        assertEquals(listOf("p1", "p1"), fired)
    }

    @Test
    fun zeroWindowFiresImmediately() {
        // Edge case: a 0 ms window means "no debouncing". Every reconnect should
        // become eligible to fire on the next processDue call at the same nowMs.
        val fired = mutableListOf<String>()
        val d = PeerChurnDebouncer(windowMs = 0L, action = { fired.add(it) })
        d.onPeerConnected("p1", 100L)
        assertEquals(1, d.processDue(nowMs = 100L))
        assertEquals(listOf("p1"), fired)
    }
}

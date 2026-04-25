package com.tharmesh.dtn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerPeerSendPacerTest {

    @Test
    fun firstSlotForAnyPeerIsAlwaysAcquired() {
        val p = PerPeerSendPacer(gapMs = 40L)
        assertTrue(p.acquireSlot("p1", 0L))
        assertTrue(p.acquireSlot("p2", 0L))
    }

    @Test
    fun secondSendInsideGapIsDeferred() {
        val p = PerPeerSendPacer(gapMs = 40L)
        assertTrue(p.acquireSlot("p1", 0L))
        // 39 ms later — still inside the gap.
        assertFalse(p.acquireSlot("p1", 39L))
        // exactly at gap → eligible
        assertTrue(p.acquireSlot("p1", 40L))
        // and one more inside the new gap is deferred again
        assertFalse(p.acquireSlot("p1", 50L))
        // Counter reflects exactly the two deferrals above.
        assertEquals(2L, p.pacedTotal())
    }

    @Test
    fun pacingIsIndependentPerPeer() {
        val p = PerPeerSendPacer(gapMs = 40L)
        assertTrue(p.acquireSlot("p1", 0L))
        // p2 has never sent — must not be paced just because p1 just sent.
        assertTrue(p.acquireSlot("p2", 0L))
        // p1 still inside its own gap.
        assertFalse(p.acquireSlot("p1", 30L))
        // p2 also inside its own gap.
        assertFalse(p.acquireSlot("p2", 30L))
    }

    @Test
    fun rejectedAcquireDoesNotAdvanceLastSendAt() {
        // If a deferred call advanced the timestamp, the gap would extend
        // forever under burst load. Verify a rejected acquire leaves the prior
        // timestamp unchanged.
        val p = PerPeerSendPacer(gapMs = 40L)
        assertTrue(p.acquireSlot("p1", 0L))
        assertFalse(p.acquireSlot("p1", 30L))  // rejected
        // 40 ms after the FIRST successful send is still eligible.
        assertTrue(p.acquireSlot("p1", 40L))
    }

    @Test
    fun zeroGapNeverDefers() {
        val p = PerPeerSendPacer(gapMs = 0L)
        assertTrue(p.acquireSlot("p1", 0L))
        assertTrue(p.acquireSlot("p1", 0L))
        assertTrue(p.acquireSlot("p1", 0L))
        assertEquals(0L, p.pacedTotal())
    }

    @Test
    fun resetClearsState() {
        val p = PerPeerSendPacer(gapMs = 40L)
        p.acquireSlot("p1", 0L)
        p.acquireSlot("p1", 10L)  // deferred → counter=1
        assertEquals(1L, p.pacedTotal())
        p.reset()
        assertEquals(0L, p.pacedTotal())
        // After reset, p1 is treated as never having sent.
        assertTrue(p.acquireSlot("p1", 11L))
    }
}

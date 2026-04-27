package com.tharmesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 7.4 — locks down the consume-once semantics that drive the
 * `auto_delivered_on_reconnect` counter. The counter must fire exactly
 * once per bundleId that was queued offline, regardless of whether
 * `BundleSent` arrives twice for the same id (defensive — the mesh
 * layer dedupes, but a transport replay could still re-emit).
 */
class OfflineQueuedBundleTrackerTest {

    @Test
    fun `online send (never marked) does not auto-deliver on sent`() {
        val t = OfflineQueuedBundleTracker()
        // BundleSent arrives for a bundleId we never tracked — repository
        // sent it while peers were connected, so the auto-delivered hook
        // must NOT fire.
        assertFalse(t.consumeOnSent("b-online"))
        assertEquals(0, t.trackedCount())
    }

    @Test
    fun `offline send (marked) auto-delivers exactly once on first sent`() {
        val t = OfflineQueuedBundleTracker()
        t.markQueuedOffline("b-offline")
        assertEquals(1, t.trackedCount())

        // First BundleSent — auto-delivered on reconnect should fire.
        assertTrue(t.consumeOnSent("b-offline"))
        // Second BundleSent for the same id (defensive: a transport
        // re-emit) — must NOT fire again.
        assertFalse(t.consumeOnSent("b-offline"))
        assertEquals(0, t.trackedCount())
    }

    @Test
    fun `marking the same id twice still consumes exactly once`() {
        val t = OfflineQueuedBundleTracker()
        // Two `send()` calls produced the same bundleId-hint by accident
        // (would never happen in practice — UUID.randomUUID — but the
        // tracker is meant to be idempotent on the mark side too).
        t.markQueuedOffline("b-dup")
        t.markQueuedOffline("b-dup")
        assertEquals(1, t.trackedCount())
        assertTrue(t.consumeOnSent("b-dup"))
        assertFalse(t.consumeOnSent("b-dup"))
    }

    @Test
    fun `independent bundleIds do not cross-fire`() {
        val t = OfflineQueuedBundleTracker()
        t.markQueuedOffline("b1")
        t.markQueuedOffline("b2")
        t.markQueuedOffline("b3")
        // ACK of b2 arrives first.
        assertTrue(t.consumeOnSent("b2"))
        // b1 still pending in the offline queue.
        assertEquals(2, t.trackedCount())
        // b1 delivered next.
        assertTrue(t.consumeOnSent("b1"))
        // b4 (never tracked) — no fire.
        assertFalse(t.consumeOnSent("b4"))
        // b3 delivered last.
        assertTrue(t.consumeOnSent("b3"))
        assertEquals(0, t.trackedCount())
    }

    @Test
    fun `concurrent consumeOnSent for the same id wins exactly once`() {
        val t = OfflineQueuedBundleTracker()
        t.markQueuedOffline("b-race")

        // Two BundleSent events arrive in parallel for the same bundle.
        // Without the synchronized lock, both threads could observe the
        // id present and both fire the counter — the lock is what makes
        // exactly one of them win the remove().
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val wins = AtomicInteger(0)

        repeat(2) {
            pool.submit {
                ready.countDown()
                go.await()
                if (t.consumeOnSent("b-race")) wins.incrementAndGet()
            }
        }
        ready.await(2, TimeUnit.SECONDS)
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))

        assertEquals(1, wins.get())
        assertEquals(0, t.trackedCount())
    }
}

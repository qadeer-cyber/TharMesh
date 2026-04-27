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
 * Stage 7.9 — locks in the fire-once-per-bundleId guarantee on the diagnostic
 * counter dedup helper. Pure unit test; no DB, no transport, no Robolectric.
 */
class BoundedIdSetTest {

    @Test
    fun addReturnsTrueOnlyOnce_perId() {
        val s = BoundedIdSet(maxSize = 16)
        assertTrue(s.add("bid-1"))
        assertFalse(s.add("bid-1"))
        assertFalse(s.add("bid-1"))
        assertEquals(1, s.size())

        assertTrue(s.add("bid-2"))
        assertFalse(s.add("bid-2"))
        assertEquals(2, s.size())
    }

    @Test
    fun evictsEldestEntry_whenAtCapacity() {
        val s = BoundedIdSet(maxSize = 3)
        assertTrue(s.add("a"))
        assertTrue(s.add("b"))
        assertTrue(s.add("c"))
        assertEquals(3, s.size())

        // Adding a 4th id evicts the eldest ("a"); set stays bounded.
        assertTrue(s.add("d"))
        assertEquals(3, s.size())

        // The evicted id is now treated as new again — semantically this is
        // fine for the diagnostic-counter use case because (a) by the time
        // a counter id has aged out of a 4 096-entry set, the underlying
        // bundle is long gone, and (b) the counter is best-effort.
        assertTrue(s.add("a"))
        assertEquals(3, s.size())
    }

    @Test
    fun isThreadSafe_underConcurrentInsertions() {
        // Stress the lock with N writers all trying to insert the same set
        // of ids. Exactly one writer per id must observe `true`; the rest
        // must observe `false`. Detects the dropped-update race in any
        // future "optimised" lock-free rewrite.
        val s = BoundedIdSet(maxSize = 1024)
        val ids = (0 until 200).map { "bid-$it" }
        val perWriter = AtomicInteger(0)
        val latch = CountDownLatch(8)
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                pool.submit {
                    try {
                        for (id in ids) {
                            if (s.add(id)) perWriter.incrementAndGet()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        // Across all 8 writers, exactly 200 trues must have been observed
        // (one per unique id). The set itself contains all 200.
        assertEquals(200, perWriter.get())
        assertEquals(200, s.size())
    }
}

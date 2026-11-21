package com.tharmesh.transport.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 0.9 added [NearbyMeshConnectPolicy.pendingConnectionCount], which the chat UI
 * reads on the main thread (ChatActivity -> TharMeshApp.meshPendingConnectionCount)
 * while Nearby lifecycle callbacks and the connect-timeout runnable mutate the
 * same maps on other threads. With plain unguarded HashMaps that is a data race
 * whose symptom is a ConcurrentModificationException or a corrupted bucket chain
 * while the user is simply looking at a chat.
 *
 * This hammers the policy from several threads and fails on any escaped throwable.
 */
class NearbyMeshConnectPolicyConcurrencyTest {

    @Test
    fun concurrentLifecycleAndUiReads_doNotRaise() {
        val policy = NearbyMeshConnectPolicy(debounceMs = 0L, connectTimeoutMs = 1_000L)
        val errors: MutableList<Throwable> = CopyOnWriteArrayList()
        val threads = 6
        val iterations = 2_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { idx ->
            Thread {
                try {
                    start.await()
                    for (i in 0 until iterations) {
                        val peer = "peer-${i % 25}"
                        when (idx % 3) {
                            0 -> {
                                policy.allowConnectionRequest(peer)
                                policy.pendingTimedOut(peer)
                            }
                            1 -> {
                                policy.recordConnectFailure(peer)
                                policy.backoffRemainingMs(peer)
                                policy.markConnected(peer)
                            }
                            // The UI reader.
                            else -> policy.pendingConnectionCount()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        start.countDown()
        assertTrue("workers must finish", done.await(60, TimeUnit.SECONDS))
        assertEquals("no thread may observe a corrupted policy: $errors", 0, errors.size)
        assertTrue("pending count must stay non-negative", policy.pendingConnectionCount() >= 0)
    }

    @Test
    fun backoffCurveIsBoundedAndMonotonic() {
        val policy = NearbyMeshConnectPolicy(
            minReconnectBackoffMs = 1_000L,
            maxReconnectBackoffMs = 30_000L
        )
        assertEquals(0L, policy.reconnectBackoffMs(0))
        assertEquals(0L, policy.reconnectBackoffMs(-3))
        assertEquals(1_000L, policy.reconnectBackoffMs(1))
        assertEquals(2_000L, policy.reconnectBackoffMs(2))
        assertEquals(16_000L, policy.reconnectBackoffMs(5))
        // Clamped, never unbounded growth or overflow.
        assertEquals(30_000L, policy.reconnectBackoffMs(6))
        assertEquals(30_000L, policy.reconnectBackoffMs(64))
        assertEquals(30_000L, policy.reconnectBackoffMs(Int.MAX_VALUE / 2))
    }
}

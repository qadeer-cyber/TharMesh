package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PR #14 follow-up — the engine's `closed` flag must make [MeshEngine.start]
 * a no-op after [MeshEngine.stop] has been called on the same instance. This
 * closes a TOCTOU race in `TharMeshApp.ensureMeshStarted()` where the
 * deferred-start coroutine's pre-flight gate could pass and then `stopMesh()`
 * could interleave before `capturedEngine.start()` actually ran, resurrecting
 * a transport that was just torn down.
 *
 * We assert at the [Transport] level (the engine's only side-effect surface)
 * because any "started" state on `MeshEngine` itself is private; the contract
 * we care about is "after stop(), start() must not call transport.start()".
 */
class MeshEngineLifecycleTest {

    private class RecordingTransport : Transport {
        val starts = mutableListOf<String>()
        var stopCount: Int = 0
        override fun setListener(listener: (TransportEvent) -> Unit) = Unit
        override fun start(localPeerId: String) {
            starts.add(localPeerId)
        }
        override fun stop() {
            stopCount++
        }
        override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean = true
    }

    @Test
    fun startAfterStop_isNoOp_doesNotResurrectTransport() {
        val transport = RecordingTransport()
        val engine = MeshEngine(localUserId = "alice", transport = transport)

        engine.start()
        engine.stop()
        engine.start()

        assertEquals(
            "second start() after stop() must NOT re-call transport.start()",
            listOf("alice"),
            transport.starts
        )
        assertEquals(1, transport.stopCount)
    }

    /**
     * PR #15 round-2 review: the simple `if (closed.get()) return` at the top
     * of [MeshEngine.start] is not enough on its own — there is a wide window
     * between that check and the actual `transport.start()` call where the
     * engine does Room I/O (`bundleStore.deleteExpired` + `loadActive`). If
     * `stop()` runs from the main thread during that window, the engine sets
     * `closed=true` and tears down the transport, but the in-flight `start()`
     * still goes on to call `transport.start()` afterwards, resurrecting it
     * on the singleton ConnectionsClient.
     *
     * This test simulates that race: the in-flight `start()` is paused
     * inside the rehydrate path (via a [BundleStore] that blocks on a
     * latch), `stop()` runs on another thread, then the rehydrate path
     * unblocks. After `start()` returns, the transport must NOT have been
     * started (because `stop()` won the race), and the transport's stop
     * count must be exactly 1 (not 2 — i.e. `stop()` must not have torn
     * down a transport that `start()` resurrected).
     */
    @Test
    fun stopDuringSlowRehydrate_doesNotResurrectTransport() {
        val transport = RecordingTransport()
        val rehydrateLatch = CountDownLatch(1)
        val rehydrateInProgress = CountDownLatch(1)
        // BundleStore that blocks loadActive until stop() has run on the
        // other thread, simulating slow Room I/O during start().
        val store = object : BundleStore {
            override fun upsert(bundle: MeshBundle) = Unit
            override fun loadActive(nowMs: Long): List<MeshBundle> {
                rehydrateInProgress.countDown()
                rehydrateLatch.await(5, TimeUnit.SECONDS)
                return emptyList()
            }
            override fun updateStatus(bundleId: String, status: String) = Unit
            override fun delete(bundleId: String) = Unit
            override fun deleteExpired(nowMs: Long): Int = 0
        }
        val engine = MeshEngine(
            localUserId = "alice",
            transport = transport,
            bundleStore = store
        )

        val starter = Thread {
            engine.start()
        }
        starter.start()
        // Wait until start() is suspended inside the rehydrate path —
        // closed is still false, transport not yet started.
        assertTrue(
            "starter thread did not enter rehydrate within 5s",
            rehydrateInProgress.await(5, TimeUnit.SECONDS)
        )
        assertEquals(
            "transport.start() must not have been called yet (rehydrate is still in progress)",
            emptyList<String>(),
            transport.starts
        )

        // Race the stop on another thread to mirror stopMesh on main while
        // ensureMeshStarted's IO coroutine is mid-rehydrate.
        val stopper = Thread { engine.stop() }
        stopper.start()
        stopper.join(5_000)
        assertFalse("stopper thread did not finish in 5s", stopper.isAlive)
        assertEquals(
            "stop() must have called transport.stop() exactly once",
            1,
            transport.stopCount
        )

        // Now release the rehydrate. start() will fall through to the
        // lock-protected re-check, see closed=true, and bail before
        // transport.start().
        rehydrateLatch.countDown()
        starter.join(5_000)
        assertFalse("starter thread did not finish in 5s", starter.isAlive)

        assertEquals(
            "start() that lost the race must NOT have called transport.start() — " +
                "doing so would resurrect the just-stopped transport on the " +
                "singleton ConnectionsClient with no reference to clean it up",
            emptyList<String>(),
            transport.starts
        )
        assertEquals(
            "transport.stop() must NOT have been called a second time — start() " +
                "should never have brought the transport back up",
            1,
            transport.stopCount
        )
    }

    @Test
    fun freshEngineForNewIdentity_isUnaffectedByPriorEnginesClosedFlag() {
        // Sign-out → sign-in cycle: TharMeshApp builds a brand-new MeshEngine
        // for the new identity. The new engine's `closed` flag is independent
        // from the old engine's, so it must start cleanly.
        val transportA = RecordingTransport()
        val engineA = MeshEngine(localUserId = "alice", transport = transportA)
        engineA.start()
        engineA.stop()

        val transportB = RecordingTransport()
        val engineB = MeshEngine(localUserId = "bob", transport = transportB)
        engineB.start()

        assertEquals(listOf("bob"), transportB.starts)
        assertFalse(
            "engine B must not be retired just because engine A was",
            transportB.starts.isEmpty()
        )
    }
}

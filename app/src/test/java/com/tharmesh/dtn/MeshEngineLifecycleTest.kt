package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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

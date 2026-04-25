package com.tharmesh.dtn

import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 5.1 — locks in the invariant that [MeshEngine.addEventListener] is a
 * passive, additive observer. The authoritative repository-owned
 * [MeshEngine.setEventListener] must still receive every event, and a passive
 * observer must see the same event stream exactly once per emission.
 *
 * Also verifies [MeshEngine.removeEventListener] unsubscribes cleanly and
 * that a passive listener receives peer-lifecycle events that do NOT go
 * through the primary listener (PeerFound / PeerDisconnected).
 */
class MeshEngineAdditiveListenerTest {

    @Test
    fun `passive listener sees bundle events alongside primary listener`() {
        val hub = LoopbackTransport.Hub()
        val primary = CopyOnWriteArrayList<MeshEvent>()
        val passive = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub))
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        alice.setEventListener { primary.add(it) }
        alice.addEventListener { passive.add(it) }
        alice.start()
        bob.start()

        alice.queueText(
            destId = "bob",
            payloadCiphertext = "hello",
            ttlMs = 60_000,
            hops = 4,
            bundleIdHint = "b-1"
        )

        assertTrue(primary.any { it is MeshEvent.BundleSent && it.bundleId == "b-1" })
        assertTrue(passive.any { it is MeshEvent.BundleSent && it.bundleId == "b-1" })
        // Every event that reached the primary listener must also reach the
        // passive listener, same count — no drops, no duplicates.
        val primarySent = primary.count { it is MeshEvent.BundleSent && it.bundleId == "b-1" }
        val passiveSent = passive.count { it is MeshEvent.BundleSent && it.bundleId == "b-1" }
        assertEquals(primarySent, passiveSent)
    }

    @Test
    fun `passive listener receives peer-connected exactly once without duplicate fanout`() {
        // LoopbackTransport only emits PeerConnected / PeerDisconnected (not
        // PeerFound, which is Nearby-specific). The critical invariant here:
        // PeerConnected goes through BOTH firePeerEvent AND emit() internally,
        // but a passive observer must still see it exactly once per emission —
        // not twice.
        val hub = LoopbackTransport.Hub()
        val passive = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub))
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        alice.addEventListener { passive.add(it) }
        alice.start()
        bob.start()

        val connected = passive.filterIsInstance<MeshEvent.PeerConnected>()
        assertTrue("expected at least one PeerConnected, got $passive", connected.isNotEmpty())
        assertEquals(1, connected.count { it.peerId == "bob" })
    }

    @Test
    fun `passive listener receives peer-found and peer-disconnected from a transport that emits them`() {
        // Synthesize PeerFound / PeerDisconnected via a stub transport so the
        // additive fan-out path for those events is exercised even though
        // LoopbackTransport doesn't use them.
        val stub = object : com.tharmesh.transport.Transport {
            private var listener: ((com.tharmesh.transport.TransportEvent) -> Unit)? = null
            override fun start(localPeerId: String) {}
            override fun stop() {}
            override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean = true
            override fun setListener(listener: (com.tharmesh.transport.TransportEvent) -> Unit) {
                this.listener = listener
            }
            fun fire(event: com.tharmesh.transport.TransportEvent) { listener?.invoke(event) }
        }
        val passive = CopyOnWriteArrayList<MeshEvent>()
        val engine = MeshEngine("alice", stub)
        engine.addEventListener { passive.add(it) }
        engine.start()

        stub.fire(com.tharmesh.transport.TransportEvent.PeerFound("p1", "Phone 1"))
        stub.fire(com.tharmesh.transport.TransportEvent.PeerDisconnected("p1"))

        val found = passive.filterIsInstance<MeshEvent.PeerFound>()
        val gone = passive.filterIsInstance<MeshEvent.PeerDisconnected>()
        assertEquals(1, found.size)
        assertEquals("p1", found[0].peerId)
        assertEquals(1, gone.size)
        assertEquals("p1", gone[0].peerId)
    }

    @Test
    fun `removeEventListener stops further delivery`() {
        val hub = LoopbackTransport.Hub()
        val passive = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub))
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        val listener: (MeshEvent) -> Unit = { passive.add(it) }
        alice.addEventListener(listener)
        alice.start()
        bob.start()
        assertTrue(passive.isNotEmpty())

        val snapshotSize = passive.size
        alice.removeEventListener(listener)
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "payload-2",
            ttlMs = 60_000,
            hops = 4,
            bundleIdHint = "b-2"
        )

        // After removal, the passive listener must not have recorded any new
        // events — it unsubscribed cleanly and the engine continues working
        // without it.
        assertEquals(snapshotSize, passive.size)
    }

    @Test
    fun `addEventListener does not displace primary listener`() {
        val hub = LoopbackTransport.Hub()
        val primary = CopyOnWriteArrayList<MeshEvent>()
        val passive = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub))
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        alice.setEventListener { primary.add(it) }
        alice.addEventListener { passive.add(it) }
        alice.start()
        bob.start()

        alice.queueText(
            destId = "bob",
            payloadCiphertext = "x",
            ttlMs = 60_000,
            hops = 4,
            bundleIdHint = "b-3"
        )
        val primaryHasSent = primary.any { it is MeshEvent.BundleSent && it.bundleId == "b-3" }
        assertTrue("primary listener must still receive BundleSent", primaryHasSent)
        assertNotNull(passive.firstOrNull { it is MeshEvent.BundleSent && it.bundleId == "b-3" })
        assertFalse(
            "primary listener must not see duplicate BundleSent",
            primary.count { it is MeshEvent.BundleSent && it.bundleId == "b-3" } > 1
        )
    }
}

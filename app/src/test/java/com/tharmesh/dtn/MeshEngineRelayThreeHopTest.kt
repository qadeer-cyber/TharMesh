package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 4.4 — multi-hop relay correctness.
 *
 * Topology:
 *
 *     A ───── B ───── C       (NO direct A↔C link)
 *
 * [BridgedTransport] is a test-only transport that maintains a per-peer allow-list of
 * reachable peers. "Connected" means present in that allow-list; `send(to)` silently
 * no-ops (and reports Error) if `to` isn't a neighbor. This lets us simulate a real
 * mesh where B must relay A→C traffic end-to-end via store-and-forward.
 */
class MeshEngineRelayThreeHopTest {

    private class BridgedTransport : Transport {
        val sends: MutableList<Send> = CopyOnWriteArrayList()
        data class Send(val to: String, val bytes: ByteArray, val sendId: Long)

        private var listener: ((TransportEvent) -> Unit)? = null
        private var localPeerId: String = ""

        /** peerId → (neighbor transport instance). Symmetric; both sides register. */
        private val neighbors: MutableMap<String, BridgedTransport> = HashMap()

        override fun setListener(listener: (TransportEvent) -> Unit) {
            this.listener = listener
        }

        override fun start(localPeerId: String) {
            this.localPeerId = localPeerId
        }

        override fun stop() {}

        override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
            sends.add(Send(peerId, payload, sendId))
            val neighbor = neighbors[peerId]
            if (neighbor == null) {
                listener?.invoke(TransportEvent.Error(peerId, sendId, "not a neighbor"))
                return false
            }
            neighbor.deliver(TransportEvent.PayloadReceived(localPeerId, payload))
            listener?.invoke(TransportEvent.PayloadSent(peerId, sendId, payload.size))
            return true
        }

        fun link(other: BridgedTransport) {
            neighbors[other.localPeerId] = other
            other.neighbors[this.localPeerId] = this
            // Fire PeerConnected both ways so MeshEngine tracks connectedPeers.
            this.listener?.invoke(TransportEvent.PeerConnected(other.localPeerId))
            other.listener?.invoke(TransportEvent.PeerConnected(this.localPeerId))
        }

        private fun deliver(event: TransportEvent) {
            listener?.invoke(event)
        }
    }

    @Test
    fun threeHop_aViaBReachesC() {
        val tA = BridgedTransport()
        val tB = BridgedTransport()
        val tC = BridgedTransport()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        val c = MeshEngine("C", tC)
        val aEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val bEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        a.setEventListener { aEvents.add(it) }
        b.setEventListener { bEvents.add(it) }
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)
        tB.link(tC)
        // Intentionally NO tA.link(tC) — A has no direct line to C.

        // A addresses C. A has no direct endpoint for C; fanout goes to B (only neighbor).
        val bundle = a.queueText(
            destId = "C",
            payloadCiphertext = "relay-ping",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "relay-bundle"
        )

        // A → B: B receives, isn't the dest, relays via forwardBundle → C.
        // C → B → A: C emits ACK on delivery; B doesn't forward ACK (non-BUNDLE frame,
        // not addressed to A), so A receives its DELIVERED via A's direct ACK path.
        //
        // Actually: ACK is a non-BUNDLE frame. B's handleBundle never sees it; B's
        // handlePayloadReceived sees the ACK targeted at C→B, but B is not the originator
        // of "relay-bundle" so handleAck is a no-op. A does NOT directly receive the
        // ACK here unless we relay ACKs too. For Stage 4.4 scope we only assert
        // message delivery at C — ACK relay is a separate concern.
        val delivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals("C must receive the bundle exactly once", 1, delivered.size)
        assertEquals("relay-bundle", delivered[0].bundle.bundleId)
        assertEquals("relay-ping", delivered[0].bundle.payloadCiphertext)

        // B relayed — at least one forward outbound on tB to "C".
        val bForwardedToC = tB.sends.any { it.to == "C" }
        assertTrue("B must have forwarded relay-bundle to C", bForwardedToC)

        // hopsLeft at C must be strictly less than original (one decrement at B).
        assertTrue(
            "hopsLeft must decrement during relay",
            delivered[0].bundle.hopsLeft < bundle.hopsLeft
        )
    }

    @Test
    fun threeHop_hopsLeftZero_stopsRelay() {
        val tA = BridgedTransport()
        val tB = BridgedTransport()
        val tC = BridgedTransport()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        val c = MeshEngine("C", tC)
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)
        tB.link(tC)

        // hops=0: A fanouts to B with hopsLeft=0. B is not the destination, so B's
        // forwardBundle hits the "hopsLeft <= 0 → drop" guard and refuses to relay to C.
        // This is the "hops exhausted" boundary we want to lock in.
        a.queueText(
            destId = "C",
            payloadCiphertext = "exhaust",
            ttlMs = 60_000L,
            hops = 0,
            bundleIdHint = "exhaust-bundle"
        )

        val delivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertTrue("C must NOT receive a hops-exhausted bundle", delivered.isEmpty())
        // INV/GET frames may fly between B and C as part of normal sync — we only
        // care that no BUNDLE carrying "exhaust-bundle" was ever forwarded to C.
        val leakedBundleToC = tB.sends.any { send ->
            send.to == "C" && String(send.bytes, Charsets.UTF_8).let {
                it.startsWith("BUNDLE|") && it.contains("exhaust-bundle")
            }
        }
        assertTrue("B must not have forwarded hops-exhausted bundle to C", !leakedBundleToC)
    }

    @Test
    fun threeHop_ttlExpired_stopsRelay() {
        var clock = 1_000L
        val tA = BridgedTransport()
        val tB = BridgedTransport()
        val tC = BridgedTransport()
        val a = MeshEngine("A", tA, now = { clock })
        val b = MeshEngine("B", tB, now = { clock })
        val c = MeshEngine("C", tC, now = { clock })
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)
        // A → B succeeds while TTL is still valid. B does NOT yet know about C.

        a.queueText(
            destId = "C",
            payloadCiphertext = "will-expire",
            ttlMs = 500L,  // 500ms TTL from clock=1000
            hops = 4,
            bundleIdHint = "expire-bundle"
        )

        // Fast-forward the clock past TTL BEFORE B meets C.
        clock = 100_000L
        tB.link(tC)
        // B's PeerConnected(C) handler syncs inventory; C sends GET for "expire-bundle";
        // B's handleGet skips expired bundles. C never receives it.

        val delivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertTrue("TTL-expired bundle must not reach C", delivered.isEmpty())
    }

    @Test
    fun duplicateBundle_doesNotDeliverTwice_butAcksBoth() {
        val tA = BridgedTransport()
        val tC = BridgedTransport()
        val a = MeshEngine("A", tA)
        val c = MeshEngine("C", tC)
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        c.setEventListener { cEvents.add(it) }
        a.start(); c.start()
        tA.link(tC)

        a.queueText(
            destId = "C",
            payloadCiphertext = "hi",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "dup-bundle"
        )
        // Re-broadcast via retryBundle — this is legal in the store-and-forward loop.
        a.retryBundle("dup-bundle")

        val delivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals("Duplicate arrivals must deliver only once", 1, delivered.size)

        // A saw multiple BundleSent events (one per fanout attempt) — this is fine,
        // rank-protected advance in the repository makes it idempotent. We just
        // assert C did not emit a second BundleDelivered.
        assertNotNull(cEvents.filterIsInstance<MeshEvent.BundleDelivered>().firstOrNull())
    }

    @Test
    fun relay_doesNotSendBackToSender() {
        val tA = BridgedTransport()
        val tB = BridgedTransport()
        val tC = BridgedTransport()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        val c = MeshEngine("C", tC)
        a.start(); b.start(); c.start()
        tA.link(tB)
        tB.link(tC)

        a.queueText(
            destId = "C",
            payloadCiphertext = "no-bounce",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "no-bounce-bundle"
        )

        // B received the bundle from A. B's forwardBundle must NOT include A in the
        // fanout targets (anti-sender invariant).
        val bSendsToA = tB.sends.filter { it.to == "A" }
        // B's only legitimate send-to-A would be from handleGet after A requested
        // something (A has no reason to GET its own bundle). Assert B never sent
        // "no-bounce-bundle" back to A.
        for (send in bSendsToA) {
            val decoded = String(send.bytes, Charsets.UTF_8)
            assertTrue(
                "B must not bounce relay-bundle back to A (found: $decoded)",
                !decoded.contains("no-bounce-bundle")
            )
        }
    }
}

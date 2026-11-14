package com.tharmesh.dtn

import com.tharmesh.transport.test.BridgedTransport
import com.tharmesh.transport.test.FaultInjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic transport faults on the real [MeshEngine] (simulated multi-hop).
 * Category: **Three-peer simulation (JVM)** — not physical phones.
 */
class MeshEngineFaultInjectionTest {

    @Test
    fun threeHop_dropFirstForward_thenRetryBundle_reachesDestination() {
        val tA = FaultInjectionTransport()
        val tB = FaultInjectionTransport()
        val tC = FaultInjectionTransport()
        tA.dropOutboundCount = 1
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        val c = MeshEngine("C", tC)
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)
        tB.link(tC)

        val bundle = a.queueText(
            destId = "C",
            payloadCiphertext = "fault-inject-msg",
            ttlMs = 120_000,
            hops = 4,
            bundleIdHint = "bid-fault-1"
        )
        if (cEvents.none { it is MeshEvent.BundleDelivered }) {
            tA.dropOutboundCount = 0
            a.retryBundle(bundle.bundleId)
        }
        val delivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals("fault-inject-msg", delivered[0].bundle.payloadCiphertext)
    }

    @Test
    fun threeHop_duplicateWirePayload_doesNotDoubleDeliver() {
        val tA = FaultInjectionTransport()
        val tB = FaultInjectionTransport()
        val tC = FaultInjectionTransport()
        tB.duplicateOutbound = true
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        val c = MeshEngine("C", tC)
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)
        tB.link(tC)

        a.queueText(
            destId = "C",
            payloadCiphertext = "dup-wire",
            ttlMs = 60_000,
            hops = 4,
            bundleIdHint = "bid-dup"
        )
        assertEquals(1, cEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)
    }

    @Test
    fun threeHop_linkCAfterQueue_aRetry_deliversWhenTopologyCompletes() {
        val tA = BridgedTransport()
        val tB = BridgedTransport()
        val tC = BridgedTransport()
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        val c = MeshEngine("C", tC)
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)

        a.queueText(
            destId = "C",
            payloadCiphertext = "delayed-dest",
            ttlMs = 120_000,
            hops = 4,
            bundleIdHint = "bid-delay"
        )
        tB.link(tC)
        a.retryBundle("bid-delay")
        b.retryBundle("bid-delay")
        assertNotNull(cEvents.filterIsInstance<MeshEvent.BundleDelivered>().singleOrNull())
    }

    @Test
    fun bridgedTransport_unlink_clearsNeighborMap() {
        val tA = BridgedTransport()
        val tB = BridgedTransport()
        MeshEngine("A", tA).start()
        MeshEngine("B", tB).start()
        tA.link(tB)
        tA.unlink(tB)
        assertEquals(false, tA.send("B", byteArrayOf(1), 1L))
    }
}
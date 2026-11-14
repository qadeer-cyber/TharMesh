package com.tharmesh.dtn

import com.tharmesh.transport.test.FaultInjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * INV/GET anti-entropy survives loss of the first INV on the wire.
 */
class MeshEngineInvGetAntiEntropyTest {

    @Test
    fun firstInvDrop_partitionHeal_syncEventuallyDelivers() {
        val tA = FaultInjectionTransport()
        val tB = FaultInjectionTransport()
        val tC = FaultInjectionTransport()
        tB.dropOutboundMatching = { payload ->
            String(payload, Charsets.UTF_8).startsWith("INV|")
        }
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        val c = MeshEngine("C", tC)
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)

        a.queueText(
            destId = "C",
            payloadCiphertext = "inv-loss",
            ttlMs = 120_000L,
            hops = 4,
            bundleIdHint = "inv-loss-1"
        )
        assertEquals(0, cEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)

        tB.link(tC)
        b.syncWithPeer("C")
        assertTrue("first INV must be dropped", tB.outboundDropped >= 1)
        assertEquals(0, cEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)

        tB.dropOutboundMatching = null
        b.syncWithPeer("C")
        assertEquals(1, cEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)
    }
}
package com.tharmesh.dtn

import com.tharmesh.transport.test.FaultInjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Malicious relay duplicate forward — JVM simulation on real [MeshEngine] path.
 */
class MeshEnginePhase3RelayDuplicationTest {

    @Test
    fun maliciousRelayDuplicateWirePayload_singleDelivery() {
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
        a.queueText("C", "dup-relay", 60_000L, 4, bundleIdHint = "bid-dup-relay")
        assertTrue(
            "duplicate injection must exercise relay outbound sends",
            tB.outboundSendAttempts >= 2
        )
        val delivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals("dup-relay", delivered[0].bundle.payloadCiphertext)
    }
}
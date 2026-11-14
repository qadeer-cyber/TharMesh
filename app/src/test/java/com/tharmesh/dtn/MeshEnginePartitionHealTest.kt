package com.tharmesh.dtn

import com.tharmesh.transport.test.BridgedTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MeshEnginePartitionHealTest {

    @Test
    fun partition_unlinkBC_thenRelink_deliversOnHealSync() {
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
        tB.link(tC)

        a.queueText("C", "partition", 120_000L, 4, bundleIdHint = "part-1")
        assertEquals(1, cEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)

        tB.unlink(tC)
        a.queueText("C", "after-partition", 120_000L, 4, bundleIdHint = "part-2")
        assertEquals(1, cEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)

        tB.link(tC)
        assertEquals(
            "heal sync must deliver queued bundle without new origin retry",
            2,
            cEvents.filterIsInstance<MeshEvent.BundleDelivered>().size
        )
        val lastDelivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>().last()
        assertEquals("after-partition", lastDelivered.bundle.payloadCiphertext)
    }
}
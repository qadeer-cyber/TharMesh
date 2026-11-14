package com.tharmesh.dtn

import com.tharmesh.transport.test.FaultInjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Relay durable storage survives process restart (simulated engine stop/start).
 * Category: JVM simulation — not physical device evidence.
 */
class MeshEngineRelayProcessRestartTest {

    private class MemStore : BundleStore {
        val rows = ConcurrentHashMap<String, MeshBundle>()
        override fun upsert(bundle: MeshBundle) { rows[bundle.bundleId] = bundle }
        override fun loadActive(nowMs: Long): List<MeshBundle> =
            rows.values.filter { it.ttlUntil >= nowMs }
        override fun updateStatus(bundleId: String, status: String) {
            rows[bundleId]?.let { rows[bundleId] = it.copy(status = status) }
        }
        override fun delete(bundleId: String) { rows.remove(bundleId) }
        override fun deleteExpired(nowMs: Long): Int {
            val gone = rows.filterValues { it.ttlUntil < nowMs }.keys.toList()
            gone.forEach { rows.remove(it) }
            return gone.size
        }
    }

    @Test
    fun relayBundle_survivesRelayEngineRestart_thenDeliversToC() {
        val store = MemStore()
        val tA = FaultInjectionTransport()
        val tB = FaultInjectionTransport()
        val tC = FaultInjectionTransport()
        tB.dropOutboundCount = 1
        val cEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB, bundleStore = store)
        val c = MeshEngine("C", tC)
        c.setEventListener { cEvents.add(it) }
        a.start(); b.start(); c.start()
        tA.link(tB)

        val bundle = a.queueText("C", "persist-relay", 120_000L, 4, bundleIdHint = "relay-restart-1")
        assertEquals(1, tB.outboundDropped)
        assertTrue(cEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
        assertNotNull(store.rows[bundle.bundleId])

        b.stop()
        tB.dropOutboundCount = 0
        tB.link(tC)
        val b2 = MeshEngine("B", tB, bundleStore = store)
        b2.start()
        assertTrue("originator retry after relay restart", a.retryBundle(bundle.bundleId))

        val delivered = cEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals("persist-relay", delivered[0].bundle.payloadCiphertext)
    }
}
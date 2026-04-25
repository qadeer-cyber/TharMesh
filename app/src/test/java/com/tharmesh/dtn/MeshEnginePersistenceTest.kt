package com.tharmesh.dtn

import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Locks in Stage 4.5 (persistent bundle cache) correctness. Uses an in-memory
 * [BundleStore] stand-in so these tests can run under unit-test Robolectric-less
 * conditions without a real Room database.
 */
class MeshEnginePersistenceTest {

    /**
     * Minimal in-memory [BundleStore]. Thread-safe via [ConcurrentHashMap]; tracks
     * [deleteExpiredCalls] so tests can assert the repository tick actually sweeps.
     */
    private class FakeBundleStore(
        private val clock: () -> Long = { System.currentTimeMillis() }
    ) : BundleStore {
        val rows: ConcurrentHashMap<String, MeshBundle> = ConcurrentHashMap()
        var deleteExpiredCalls: Int = 0
            private set

        override fun upsert(bundle: MeshBundle) {
            rows[bundle.bundleId] = bundle
        }

        override fun loadActive(nowMs: Long): List<MeshBundle> =
            rows.values
                .filter { it.ttlUntil >= nowMs }
                .sortedBy { it.ttlUntil }
                .toList()

        override fun updateStatus(bundleId: String, status: String) {
            rows[bundleId]?.let { rows[bundleId] = it.copy(status = status) }
        }

        override fun delete(bundleId: String) {
            rows.remove(bundleId)
        }

        override fun deleteExpired(nowMs: Long): Int {
            deleteExpiredCalls++
            val expired = rows.values.filter { it.ttlUntil < nowMs }.map { it.bundleId }
            expired.forEach { rows.remove(it) }
            return expired.size
        }
    }

    @Test
    fun queueText_persistsBundleToStore() {
        val store = FakeBundleStore()
        val hub = LoopbackTransport.Hub()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        alice.start()

        val bundle = alice.queueText(
            destId = "bob",
            payloadCiphertext = "persist-me",
            ttlMs = 60_000L,
            hops = 3,
            bundleIdHint = "persist-1"
        )

        assertNotNull("queueText should upsert into the store", store.rows[bundle.bundleId])
        assertEquals(bundle.payloadCiphertext, store.rows[bundle.bundleId]?.payloadCiphertext)
        assertEquals(bundle.hopsLeft, store.rows[bundle.bundleId]?.hopsLeft)
    }

    @Test
    fun start_restoresActiveBundlesIntoCache() {
        val store = FakeBundleStore()
        // Seed the store as if a previous process had queued two bundles.
        val nowMs = System.currentTimeMillis()
        store.upsert(
            MeshBundle(
                bundleId = "alive",
                srcId = "alice",
                destId = "bob",
                payloadCiphertext = "hi",
                ttlUntil = nowMs + 60_000L,
                hopsLeft = 3,
                signature = "TODO_SIG",
                status = "PENDING"
            )
        )
        store.upsert(
            MeshBundle(
                bundleId = "expired",
                srcId = "alice",
                destId = "bob",
                payloadCiphertext = "stale",
                ttlUntil = nowMs - 1L,
                hopsLeft = 3,
                signature = "TODO_SIG",
                status = "PENDING"
            )
        )

        val hub = LoopbackTransport.Hub()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        alice.start()

        // Active bundle must be in the live cache; expired must be GONE from both
        // the cache AND the store (start() calls deleteExpired).
        assertEquals(1, alice.cacheSize())
        assertTrue("expired row should have been swept from disk on start",
            store.rows["expired"] == null)
        assertNotNull("active row should remain in the store", store.rows["alive"])
    }

    @Test
    fun ackFlipsPersistedStatusToDeliveredFinal() {
        val store = FakeBundleStore()
        val hub = LoopbackTransport.Hub()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        alice.start(); bob.start()

        alice.queueText(
            destId = "bob",
            payloadCiphertext = "hey",
            ttlMs = 60_000L,
            hops = 3,
            bundleIdHint = "ack-1"
        )

        assertEquals(
            "Alice's outbound bundle should be marked DELIVERED_FINAL on disk once Bob ACKs",
            "DELIVERED_FINAL",
            store.rows["ack-1"]?.status
        )
    }

    @Test
    fun sweepExpiredPersistent_drivesStoreDeleteExpired() {
        val store = FakeBundleStore()
        val hub = LoopbackTransport.Hub()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        alice.start()

        val before = store.deleteExpiredCalls
        alice.sweepExpiredPersistent()
        assertEquals(
            "sweepExpiredPersistent must delegate to BundleStore.deleteExpired",
            before + 1,
            store.deleteExpiredCalls
        )
    }

    @Test
    fun secondEngineRehydratesWithoutRedelivery() {
        // Simulate A1 (crashed) and A2 (fresh process on same store). A2 must not
        // re-fire BundleDelivered for a bundle already marked DELIVERED_FINAL on disk.
        val store = FakeBundleStore()
        val nowMs = System.currentTimeMillis()
        store.upsert(
            MeshBundle(
                bundleId = "already-done",
                srcId = "alice",
                destId = "bob",
                payloadCiphertext = "old",
                ttlUntil = nowMs + 60_000L,
                hopsLeft = 3,
                signature = "TODO_SIG",
                status = "DELIVERED_FINAL"
            )
        )
        val hub = LoopbackTransport.Hub()
        val events: MutableList<MeshEvent> = java.util.concurrent.CopyOnWriteArrayList()
        val bob = MeshEngine("bob", LoopbackTransport(hub), bundleStore = store)
        bob.setEventListener { events.add(it) }
        bob.start()

        val delivered = events.filterIsInstance<MeshEvent.BundleDelivered>()
        assertTrue(
            "cold-start restore must NOT replay BundleDelivered for DELIVERED_FINAL rows",
            delivered.isEmpty()
        )
    }
}

package com.tharmesh.dtn

import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 7.9 — locks in the split [MeshEngine.prepareOutbound] /
 * [MeshEngine.broadcastOutbound] contract that
 * [com.tharmesh.data.MessageRepository.send] relies on for process-death-safe
 * outbound ordering.
 *
 * The legacy `queueText` was a single call that signed-cached-persisted-AND-
 * broadcast a bundle. With the message-row insert happening AFTER queueText,
 * a process kill between transaction commit and queueText left the
 * messages.bundleId row QUEUED with no matching bundles row, which the
 * retry loop could not recover. The new shape lets the repository:
 *
 *   1. prepareOutbound — sign + cache + persist (durable, no wire traffic)
 *   2. db.runInTransaction { insert msg row }
 *   3. broadcastOutbound — fan out on the wire
 *
 * Crash semantics:
 *   - kill between 1 and 2 → orphan bundle (harmless; ages out at TTL)
 *   - kill between 2 and 3 → durable row + durable bundle (next cold start
 *     re-broadcasts via retry loop)
 */
class MeshEnginePrepareOutboundTest {

    /** Same minimal fake as MeshEnginePersistenceTest. Duplicated locally to
     * keep the test self-contained — they exercise different invariants. */
    private class FakeBundleStore : BundleStore {
        val rows: ConcurrentHashMap<String, MeshBundle> = ConcurrentHashMap()
        override fun upsert(bundle: MeshBundle) {
            rows[bundle.bundleId] = bundle
        }
        override fun loadActive(nowMs: Long): List<MeshBundle> =
            rows.values.filter { it.ttlUntil >= nowMs }.toList()
        override fun updateStatus(bundleId: String, status: String) {
            rows[bundleId]?.let { rows[bundleId] = it.copy(status = status) }
        }
        override fun delete(bundleId: String) {
            rows.remove(bundleId)
        }
        override fun deleteExpired(nowMs: Long): Int {
            val expired = rows.values.filter { it.ttlUntil < nowMs }.map { it.bundleId }
            expired.forEach { rows.remove(it) }
            return expired.size
        }
    }

    @Test
    fun prepareOutbound_persistsBundle_butDoesNotBroadcast() {
        val store = FakeBundleStore()
        val hub = LoopbackTransport.Hub()
        val aliceEvents = CopyOnWriteArrayList<MeshEvent>()
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        alice.setEventListener { aliceEvents.add(it) }
        bob.setEventListener { bobEvents.add(it) }
        alice.start()
        bob.start()

        val bundle = alice.prepareOutbound(
            destId = "bob",
            payloadCiphertext = "preparation-only",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "prep-1"
        )

        // Bundle is durable in BundleStore — required for cold-start
        // recovery if a process kill happens before broadcastOutbound.
        assertNotNull(store.rows["prep-1"])
        assertEquals("preparation-only", store.rows["prep-1"]?.payloadCiphertext)

        // Bundle is in cache, so the retry loop can pick it up.
        assertTrue(alice.hasCachedBundle("prep-1"))

        // No wire traffic — Bob did not receive anything.
        val deliveredAtBob = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertTrue("Bob must NOT have received the bundle", deliveredAtBob.isEmpty())
        // Alice did not emit BundleSending either — the broadcast path is gated.
        val sendingAtAlice = aliceEvents.filterIsInstance<MeshEvent.BundleSending>()
        assertTrue("Alice must NOT have emitted BundleSending", sendingAtAlice.isEmpty())

        // Sanity: returned bundle matches the persisted one.
        assertEquals("prep-1", bundle.bundleId)
        assertEquals("alice", bundle.srcId)
        assertEquals("bob", bundle.destId)
    }

    @Test
    fun broadcastOutbound_afterPrepare_deliversExactlyOnce() {
        val store = FakeBundleStore()
        val hub = LoopbackTransport.Hub()
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        bob.setEventListener { bobEvents.add(it) }
        alice.start()
        bob.start()

        val bundle = alice.prepareOutbound(
            destId = "bob",
            payloadCiphertext = "two-phase",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "prep-broadcast-1"
        )
        // Pre-broadcast — Bob has nothing.
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())

        alice.broadcastOutbound(bundle)

        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals("prep-broadcast-1", delivered[0].bundle.bundleId)
        assertEquals("two-phase", delivered[0].bundle.payloadCiphertext)
    }

    @Test
    fun queueText_remainsBackwardsCompatible_persistsAndBroadcastsInOneShot() {
        // Pre-7.9 callers (and tests) use the queueText facade, which now
        // delegates to prepareOutbound + broadcastOutbound. Same observable
        // behaviour as before: persisted, cached, broadcast.
        val store = FakeBundleStore()
        val hub = LoopbackTransport.Hub()
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        bob.setEventListener { bobEvents.add(it) }
        alice.start()
        bob.start()

        alice.queueText(
            destId = "bob",
            payloadCiphertext = "legacy-shape",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "legacy-1"
        )

        assertNotNull(store.rows["legacy-1"])
        assertTrue(alice.hasCachedBundle("legacy-1"))
        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals("legacy-1", delivered[0].bundle.bundleId)
    }

    @Test
    fun hasCachedBundle_reflectsActualCacheState() {
        val store = FakeBundleStore()
        val hub = LoopbackTransport.Hub()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        alice.start()

        assertFalse(alice.hasCachedBundle("never-existed"))

        alice.prepareOutbound(
            destId = "bob",
            payloadCiphertext = "x",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "cached-1"
        )
        assertTrue(alice.hasCachedBundle("cached-1"))
    }
}

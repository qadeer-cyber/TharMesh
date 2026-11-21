package com.tharmesh.dtn

import com.tharmesh.transport.test.FaultInjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Offline send -> reconnect -> delivery -> acknowledgment, end to end on the real
 * [MeshEngine] over the bridged test transport.
 *
 * **JVM simulation — not physical-device evidence.** No radio, no Play Services,
 * no Doze. Physical two-phone validation remains NOT EXECUTED (docs/field-uat-0.9.md).
 *
 * What this does prove, with assertions that fail if the behaviour breaks:
 *  - a message composed with no peers in range is retained, not dropped or failed,
 *  - after the link comes up the store-and-forward flush delivers it,
 *  - the recipient delivers it exactly once, and a second flush does not duplicate it,
 *  - the sender observes BundleSent and then an acknowledgment for that bundleId,
 *  - a mid-flight disconnect followed by reconnect still converges on one delivery.
 */
class MeshOfflineReconnectRecoveryTest {

    private fun deliveries(events: List<MeshEvent>, bundleId: String) =
        events.filterIsInstance<MeshEvent.BundleDelivered>()
            .filter { it.bundle.bundleId == bundleId }

    @Test
    fun messageQueuedWhileOffline_deliversOnceAfterReconnect_andIsAcked() {
        val tA = FaultInjectionTransport()
        val tB = FaultInjectionTransport()
        val aEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val bEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        a.setEventListener { aEvents.add(it) }
        b.setEventListener { bEvents.add(it) }
        a.start()
        b.start()

        // No link yet: A is offline with respect to B.
        assertTrue("A must report no connected peers while unlinked", !a.hasConnectedPeers())
        val bundle = a.queueText("B", "offline-then-reconnect", 300_000L, 4, bundleIdHint = "bid-offline-1")

        assertTrue(
            "nothing may be delivered while the mesh is down",
            bEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty()
        )
        assertTrue(
            "an offline compose must not be reported as sent",
            aEvents.filterIsInstance<MeshEvent.BundleSent>().isEmpty()
        )
        assertTrue(
            "the bundle must be retained locally for store-and-forward",
            a.hasCachedBundle(bundle.bundleId)
        )

        // Radio comes back.
        tA.link(tB)
        assertTrue("A must see B after the link", a.hasConnectedPeers())
        a.retryAllPendingForLocalUser()

        val delivered = deliveries(bEvents, bundle.bundleId)
        assertEquals("exactly one delivery after reconnect", 1, delivered.size)
        assertEquals("offline-then-reconnect", delivered[0].bundle.payloadCiphertext)
        // The sender must end up with positive confirmation for this bundle. Note the
        // reconnect flush can complete either through the broadcast path (which
        // correlates a sendId and emits BundleSent) or through the anti-entropy
        // INV/GET sync that PeerConnected kicks off (sendId 0, so no BundleSent).
        // Either way an acknowledgment must come back, which is what moves the row to
        // DELIVERED in MessageRepository.
        val confirmations = aEvents.filter {
            (it is MeshEvent.BundleSent && it.bundleId == bundle.bundleId) ||
                (it is MeshEvent.BundleAcked && it.bundleId == bundle.bundleId)
        }
        assertTrue(
            "sender must observe BundleSent and/or BundleAcked after reconnect, saw: " +
                aEvents.map { it::class.simpleName },
            confirmations.isNotEmpty()
        )
        assertTrue(
            "recipient must acknowledge the reconnect-flushed bundle",
            aEvents.filterIsInstance<MeshEvent.BundleAcked>().any { it.bundleId == bundle.bundleId }
        )

        // Idempotency: a second flush (e.g. another reconnect tick) must not re-deliver.
        a.retryAllPendingForLocalUser()
        assertEquals(
            "store-and-forward re-flush must be deduplicated at the recipient",
            1,
            deliveries(bEvents, bundle.bundleId).size
        )
    }

    @Test
    fun deliveryAcknowledgmentReachesSender_forTheSameBundleId() {
        val tA = FaultInjectionTransport()
        val tB = FaultInjectionTransport()
        val aEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        a.setEventListener { aEvents.add(it) }
        a.start()
        b.start()
        tA.link(tB)

        val bundle = a.queueText("B", "ack-me", 300_000L, 4, bundleIdHint = "bid-ack-1")

        val acked = aEvents.filterIsInstance<MeshEvent.BundleAcked>()
            .filter { it.bundleId == bundle.bundleId }
        assertEquals("recipient must acknowledge exactly once", 1, acked.size)
    }

    @Test
    fun disconnectMidSessionThenReconnect_stillConvergesOnSingleDelivery() {
        val tA = FaultInjectionTransport()
        val tB = FaultInjectionTransport()
        val bEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val a = MeshEngine("A", tA)
        val b = MeshEngine("B", tB)
        b.setEventListener { bEvents.add(it) }
        a.start()
        b.start()

        tA.link(tB)
        tA.unlink(tB)
        assertTrue("A must report the peer gone after unlink", !a.hasConnectedPeers())

        // Composed during the outage: the transport has no neighbour to accept it.
        val bundle = a.queueText("B", "survives-churn", 300_000L, 4, bundleIdHint = "bid-churn-1")
        assertTrue(
            "no delivery is possible during the outage",
            deliveries(bEvents, bundle.bundleId).isEmpty()
        )

        tA.link(tB)
        a.retryAllPendingForLocalUser()
        a.retryAllPendingForLocalUser()

        assertEquals(
            "reconnect after churn must deliver exactly once",
            1,
            deliveries(bEvents, bundle.bundleId).size
        )
    }
}

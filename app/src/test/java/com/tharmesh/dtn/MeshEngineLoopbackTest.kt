package com.tharmesh.dtn

import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Two [MeshEngine] instances glued together by a shared [LoopbackTransport.Hub]. The
 * Alice engine sends a bundle to Bob; we assert the full lifecycle (DELIVERED at Bob,
 * ACKED at Alice, READ at Alice after Bob emits a READ receipt).
 */
class MeshEngineLoopbackTest {

    private lateinit var hub: LoopbackTransport.Hub
    private lateinit var aliceEvents: MutableList<MeshEvent>
    private lateinit var bobEvents: MutableList<MeshEvent>
    private lateinit var alice: MeshEngine
    private lateinit var bob: MeshEngine

    @Before
    fun setUp() {
        hub = LoopbackTransport.Hub()
        aliceEvents = CopyOnWriteArrayList()
        bobEvents = CopyOnWriteArrayList()
        alice = MeshEngine("alice", LoopbackTransport(hub))
        bob = MeshEngine("bob", LoopbackTransport(hub))
        alice.setEventListener { aliceEvents.add(it) }
        bob.setEventListener { bobEvents.add(it) }
        alice.start()
        bob.start()
    }

    @Test
    fun sendBundle_deliveredAndAcked() {
        val bundle = alice.queueText(
            destId = "bob",
            payloadCiphertext = "hello bob",
            ttlMs = 60_000,
            hops = 4,
            bundleIdHint = "bundle-1"
        )

        // Bob must have received exactly one delivery event with our bundle.
        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals("bundle-1", delivered[0].bundle.bundleId)
        assertEquals("hello bob", delivered[0].bundle.payloadCiphertext)

        // Alice should see SENT (link-layer) and ACKED (from Bob's ACK frame).
        assertTrue(aliceEvents.any { it is MeshEvent.BundleSent && it.bundleId == "bundle-1" })
        assertTrue(aliceEvents.any { it is MeshEvent.BundleAcked && it.bundleId == "bundle-1" })
        assertEquals("bundle-1", bundle.bundleId)
    }

    @Test
    fun readReceipt_emitsBundleReadOnSender() {
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "secret",
            ttlMs = 60_000,
            hops = 4,
            bundleIdHint = "bundle-read"
        )
        // Bob "opens" the chat and sends READ.
        bob.sendRead("bundle-read", "alice")

        val read = aliceEvents.filterIsInstance<MeshEvent.BundleRead>().firstOrNull()
        assertNotNull("Alice should see a BundleRead event for bundle-read", read)
        assertEquals("bundle-read", read!!.bundleId)
        assertEquals("bob", read.readByUserId)
    }
}

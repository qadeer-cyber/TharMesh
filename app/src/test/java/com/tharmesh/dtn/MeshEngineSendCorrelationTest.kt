package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the A2 fix from the technical audit: [MeshEvent.BundleSent] is emitted ONLY
 * when the transport confirms the payload via [TransportEvent.PayloadSent] with the
 * matching sendId. A transport that returns `true` synchronously but never fires
 * PayloadSent must NOT flip the message to SENT — that was the stuck-✓ bug.
 *
 * Also verifies the mirror path: [TransportEvent.Error] emits [MeshEvent.BundleFailed]
 * with the bundleId, so the repository can flip the row to FAILED.
 */
class MeshEngineSendCorrelationTest {

    /** Fake transport that records sends and lets the test fire back events by hand. */
    private class FakeTransport : Transport {
        data class SendCall(val peerId: String, val sendId: Long, val payload: ByteArray)

        val sends = mutableListOf<SendCall>()
        private var listener: ((TransportEvent) -> Unit)? = null
        var acceptSends: Boolean = true

        override fun start(localPeerId: String) {}
        override fun stop() {}
        override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
            sends.add(SendCall(peerId, sendId, payload))
            return acceptSends
        }
        override fun setListener(listener: (TransportEvent) -> Unit) {
            this.listener = listener
        }

        fun fire(event: TransportEvent) {
            listener?.invoke(event)
        }
    }

    /** Declare [peerId] as a connected peer so [MeshEngine.broadcastBundle] actually sends. */
    private fun FakeTransport.connect(peerId: String) {
        fire(TransportEvent.PeerConnected(peerId))
    }

    /** Find the BUNDLE-framed send (skip the INV sync that syncWithPeer kicks off). */
    private fun FakeTransport.bundleSends(): List<FakeTransport.SendCall> =
        sends.filter { it.sendId != 0L }

    @Test
    fun bundleSent_onlyFiresAfterPayloadSent() {
        val transport = FakeTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val events = mutableListOf<MeshEvent>()
        engine.setEventListener { events.add(it) }
        transport.connect("peer")

        val bundle = engine.queueText(
            destId = "peer",
            payloadCiphertext = "hi",
            ttlMs = 60_000L,
            hops = 4
        )

        // Before PayloadSent: no BundleSent yet — the bug used to flip SENT here.
        assertTrue("No BundleSent before transport confirms",
            events.none { it is MeshEvent.BundleSent })
        assertEquals(1, transport.bundleSends().size)
        val sendId = transport.bundleSends()[0].sendId

        transport.fire(TransportEvent.PayloadSent(peerId = "peer", sendId = sendId, bytesCount = 32))

        val sent = events.filterIsInstance<MeshEvent.BundleSent>().single()
        assertEquals(bundle.bundleId, sent.bundleId)
    }

    @Test
    fun transportError_emitsBundleFailed_withSameBundleId() {
        val transport = FakeTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val events = mutableListOf<MeshEvent>()
        engine.setEventListener { events.add(it) }
        transport.connect("peer")

        val bundle = engine.queueText(
            destId = "peer",
            payloadCiphertext = "hi",
            ttlMs = 60_000L,
            hops = 4
        )
        val sendId = transport.bundleSends()[0].sendId

        transport.fire(TransportEvent.Error(peerId = "peer", sendId = sendId, reason = "disconnected"))

        val failed = events.filterIsInstance<MeshEvent.BundleFailed>().single()
        assertEquals(bundle.bundleId, failed.bundleId)
        assertTrue("No premature BundleSent on error path",
            events.none { it is MeshEvent.BundleSent })
    }

    @Test
    fun bundleSending_emittedSynchronouslyOnTransportAccept_beforeBundleSent() {
        val transport = FakeTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val events = mutableListOf<MeshEvent>()
        engine.setEventListener { events.add(it) }
        transport.connect("peer")

        val bundle = engine.queueText(
            destId = "peer",
            payloadCiphertext = "hi",
            ttlMs = 60_000L,
            hops = 4
        )

        // BundleSending fires synchronously after transport.send() returns true.
        val sending = events.filterIsInstance<MeshEvent.BundleSending>().single()
        assertEquals(bundle.bundleId, sending.bundleId)
        assertTrue("BundleSent must not precede PayloadSent",
            events.none { it is MeshEvent.BundleSent })

        val sendId = transport.bundleSends()[0].sendId
        transport.fire(TransportEvent.PayloadSent(peerId = "peer", sendId = sendId, bytesCount = 32))

        // Order: BundleSending first, then BundleSent.
        val sendingIdx = events.indexOfFirst { it is MeshEvent.BundleSending }
        val sentIdx = events.indexOfFirst { it is MeshEvent.BundleSent }
        assertTrue("BundleSending fires before BundleSent", sendingIdx < sentIdx)
    }

    @Test
    fun bundleSending_notEmittedWhenTransportRejects() {
        val transport = FakeTransport().also { it.acceptSends = false }
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val events = mutableListOf<MeshEvent>()
        engine.setEventListener { events.add(it) }
        transport.connect("peer")

        engine.queueText(
            destId = "peer",
            payloadCiphertext = "hi",
            ttlMs = 60_000L,
            hops = 4
        )

        // Transport returned false → message stays QUEUED; no SENDING / SENT emitted.
        // The retry loop will try again later.
        assertTrue("Rejected send must not emit BundleSending",
            events.none { it is MeshEvent.BundleSending })
        assertTrue("Rejected send must not emit BundleSent",
            events.none { it is MeshEvent.BundleSent })
    }

    @Test
    fun nonBundleSend_withSendIdZero_neverEmitsBundleSent() {
        val transport = FakeTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val events = mutableListOf<MeshEvent>()
        engine.setEventListener { events.add(it) }

        // sendRead uses a non-BUNDLE frame with sendId=0; a PayloadSent with sendId=0
        // must not surface as a BundleSent.
        engine.sendRead(bundleId = "some-bundle", toPeerId = "peer")
        transport.fire(TransportEvent.PayloadSent(peerId = "peer", sendId = 0L, bytesCount = 10))

        assertTrue("PayloadSent for sendId=0 must not synthesize BundleSent",
            events.none { it is MeshEvent.BundleSent })
    }
}

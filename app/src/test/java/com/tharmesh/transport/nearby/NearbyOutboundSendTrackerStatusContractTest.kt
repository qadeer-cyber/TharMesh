package com.tharmesh.transport.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for the 0.9 delivery-receipt outage.
 *
 * `NearbyOutboundSendTracker.STATUS_SUCCESS` was 3, which is Play Services'
 * `PayloadTransferUpdate.Status.IN_PROGRESS`, not SUCCESS (1). Because
 * [NearbyConnectionsTransport] forwarded the *real* GMS status, every completed
 * transfer was read as "still in flight": `PayloadSent` was never dispatched,
 * `MeshEvent.BundleSent` never fired, and every outgoing message stayed SENDING
 * forever on device. The old tracker test only ever passed `STATUS_SUCCESS`
 * symbolically, so it could not see the mis-mapping.
 *
 * These assertions are written against the literal Play Services values
 * (SUCCESS = 1, FAILURE = 2, IN_PROGRESS = 3, CANCELED = 4) so they fail if the
 * mirrors are renumbered again.
 */
class NearbyOutboundSendTrackerStatusContractTest {

    @Test
    fun mirrorsMatchPlayServicesStatusValues() {
        assertEquals(1, NearbyOutboundSendTracker.STATUS_SUCCESS)
        assertEquals(2, NearbyOutboundSendTracker.STATUS_FAILURE)
        assertEquals(3, NearbyOutboundSendTracker.STATUS_IN_PROGRESS)
        assertEquals(4, NearbyOutboundSendTracker.STATUS_CANCELED)
    }

    @Test
    fun rawStatus1_isTreatedAsCompletedTransfer() {
        val t = NearbyOutboundSendTracker()
        t.register(payloadId = 11L, peerId = "bob", sendId = 5L, totalBytes = 64)
        val sent = t.onPayloadTransferUpdate(11L, status = 1, bytesTransferred = 64, totalBytes = 64)
        assertNotNull("GMS status 1 is SUCCESS and must emit PayloadSent", sent)
        assertEquals("bob", sent?.peerId)
        assertEquals(5L, sent?.sendId)
        assertEquals(64, sent?.bytesCount)
        assertEquals("registration must be consumed", 0, t.inflightCount())
    }

    @Test
    fun rawStatus3_isInProgress_andMustNotEmit() {
        val t = NearbyOutboundSendTracker()
        t.register(payloadId = 12L, peerId = "bob", sendId = 6L, totalBytes = 64)
        assertNull(
            "GMS status 3 is IN_PROGRESS — emitting here would re-introduce the premature ✓",
            t.onPayloadTransferUpdate(12L, status = 3, bytesTransferred = 64, totalBytes = 64)
        )
        assertEquals("payload must stay in flight", 1, t.inflightCount())
    }

    @Test
    fun rawStatus2and4_doNotEmitPayloadSent() {
        val t = NearbyOutboundSendTracker()
        t.register(payloadId = 13L, peerId = "bob", sendId = 7L, totalBytes = 8)
        assertNull(t.onPayloadTransferUpdate(13L, status = 2, bytesTransferred = 0, totalBytes = 8))
        assertNull(t.onPayloadTransferUpdate(13L, status = 4, bytesTransferred = 0, totalBytes = 8))
        assertEquals(1, t.inflightCount())
    }

    @Test
    fun statusFreeSuccessPath_requiresCompleteTransfer_andIsIdempotent() {
        val t = NearbyOutboundSendTracker()
        t.register(payloadId = 14L, peerId = "carol", sendId = 9L, totalBytes = 100)
        assertNull("short transfer is not a receipt", t.onTransferSuccess(14L, 99, 100))
        assertNotNull(t.onTransferSuccess(14L, 100, 100))
        assertNull("SUCCESS must emit exactly once", t.onTransferSuccess(14L, 100, 100))
    }

    @Test
    fun unregisteredPayload_inboundTransfer_emitsNothing() {
        val t = NearbyOutboundSendTracker()
        assertNull(t.onTransferSuccess(999L, 10, 10))
        assertNull(t.onTransferFailure(999L, "inbound"))
    }

    @Test
    fun sendIdZero_isNeverTracked() {
        val t = NearbyOutboundSendTracker()
        t.register(payloadId = 15L, peerId = "dave", sendId = 0L, totalBytes = 4)
        assertEquals(0, t.inflightCount())
        assertNull("sendId 0 frames (e.g. read receipts) must not synthesize PayloadSent",
            t.onTransferSuccess(15L, 4, 4))
    }
}

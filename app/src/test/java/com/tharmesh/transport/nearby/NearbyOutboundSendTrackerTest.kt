package com.tharmesh.transport.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyOutboundSendTrackerTest {

    @Test
    fun payloadSent_onlyAfterTransferSuccess_withFullBytes() {
        val t = NearbyOutboundSendTracker()
        t.register(payloadId = 42L, peerId = "bob", sendId = 7L, totalBytes = 128)
        assertNull(t.onPayloadTransferUpdate(42L, status = 1, bytesTransferred = 64, totalBytes = 128))
        val sent = t.onPayloadTransferUpdate(
            42L,
            NearbyOutboundSendTracker.STATUS_SUCCESS,
            bytesTransferred = 128,
            totalBytes = 128
        )
        assertEquals("bob", sent?.peerId)
        assertEquals(7L, sent?.sendId)
        assertEquals(128, sent?.bytesCount)
        assertNull("duplicate SUCCESS must not re-emit", t.onPayloadTransferUpdate(42L, NearbyOutboundSendTracker.STATUS_SUCCESS, 128, 128))
    }

    @Test
    fun transferFailure_emitsErrorAndClearsRegistration() {
        val t = NearbyOutboundSendTracker()
        t.register(9L, "alice", sendId = 3L, totalBytes = 10)
        val err = t.onTransferFailure(9L, "radio_reset")
        assertEquals("alice", err?.peerId)
        assertEquals(3L, err?.sendId)
        assertTrue(err?.reason?.contains("radio_reset") == true)
        assertNull(t.onTransferFailure(9L, "again"))
    }
}
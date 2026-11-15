package com.tharmesh.transport.nearby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMeshConnectPolicyTest {

    @Test
    fun rapidReconnectRequests_debounced_noDuplicatePending() {
        var clock = 0L
        val p = NearbyMeshConnectPolicy(debounceMs = 2_000L, connectTimeoutMs = 30_000L, nowMs = { clock })
        assertTrue(p.allowConnectionRequest("p1"))
        assertFalse("second request inside debounce window must be suppressed", p.allowConnectionRequest("p1"))
        clock = 2_000L
        assertFalse("pending connect blocks another until cleared", p.allowConnectionRequest("p1"))
        p.markConnected("p1")
        assertTrue(p.allowConnectionRequest("p1"))
    }

    @Test
    fun pendingTimesOut_afterConnectTimeoutMs() {
        var clock = 0L
        val p = NearbyMeshConnectPolicy(debounceMs = 0L, connectTimeoutMs = 1_000L, nowMs = { clock })
        assertTrue(p.allowConnectionRequest("peer"))
        assertFalse(p.pendingTimedOut("peer"))
        clock = 999L
        assertFalse(p.pendingTimedOut("peer"))
        clock = 1_000L
        assertTrue(p.pendingTimedOut("peer"))
    }
}
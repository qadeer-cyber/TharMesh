package com.tharmesh.transport.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMeshConnectPolicyBackoffTest {

    @Test
    fun connectFailure_appliesExponentialBackoff_thenClearsOnSuccess() {
        var clock = 0L
        val p = NearbyMeshConnectPolicy(
            debounceMs = 0L,
            connectTimeoutMs = 60_000L,
            nowMs = { clock },
            minReconnectBackoffMs = 1_000L,
            maxReconnectBackoffMs = 8_000L
        )
        assertTrue(p.allowConnectionRequest("p1"))
        p.recordConnectFailure("p1")
        assertEquals(1_000L, p.backoffRemainingMs("p1"))
        assertFalse("backoff must block reconnect", p.allowConnectionRequest("p1"))
        clock = 500L
        assertFalse(p.allowConnectionRequest("p1"))
        clock = 1_000L
        assertTrue(p.allowConnectionRequest("p1"))
        p.markConnected("p1")
        p.recordConnectFailure("p1")
        assertEquals(2_000L, p.reconnectBackoffMs(2))
        assertEquals(4_000L, p.reconnectBackoffMs(3))
        assertEquals(8_000L, p.reconnectBackoffMs(4))
    }
}
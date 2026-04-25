package com.tharmesh.diagnostics

import com.tharmesh.dtn.MeshBundle
import com.tharmesh.dtn.MeshEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCollectorTest {

    private fun bundle(id: String) = MeshBundle(
        bundleId = id,
        srcId = "u-src",
        destId = "u-dst",
        payloadCiphertext = "",
        ttlUntil = Long.MAX_VALUE,
        hopsLeft = 3,
        signature = "",
        status = "QUEUED"
    )

    @Test
    fun `counters start at zero`() {
        val c = DiagnosticsCollector(recentCapacity = 16)
        val s = c.snapshot()
        assertEquals(0L, s.peersFound)
        assertEquals(0L, s.peersConnected)
        assertEquals(0L, s.peersDisconnected)
        assertEquals(0L, s.peersCurrentlyConnected)
        assertEquals(0L, s.bundlesSending)
        assertEquals(0L, s.bundlesSent)
        assertEquals(0L, s.bundlesDelivered)
        assertEquals(0L, s.bundlesAcked)
        assertEquals(0L, s.bundlesRead)
        assertEquals(0L, s.bundlesFailed)
        assertTrue(c.recentEvents().isEmpty())
    }

    @Test
    fun `each event increments exactly one counter`() {
        val c = DiagnosticsCollector(recentCapacity = 16)
        c.onEvent(MeshEvent.PeerFound("p1", "Phone 1"))
        c.onEvent(MeshEvent.PeerConnected("p1"))
        c.onEvent(MeshEvent.BundleSending("b1"))
        c.onEvent(MeshEvent.BundleSent("b1"))
        c.onEvent(MeshEvent.BundleDelivered(bundle("b1")))
        c.onEvent(MeshEvent.BundleAcked("b1", "u-dst"))
        c.onEvent(MeshEvent.BundleRead("b1", "u-dst"))
        c.onEvent(MeshEvent.BundleFailed("b2", "transport-error"))
        c.onEvent(MeshEvent.PeerDisconnected("p1"))

        val s = c.snapshot()
        assertEquals(1L, s.peersFound)
        assertEquals(1L, s.peersConnected)
        assertEquals(1L, s.peersDisconnected)
        assertEquals(0L, s.peersCurrentlyConnected)
        assertEquals(1L, s.bundlesSending)
        assertEquals(1L, s.bundlesSent)
        assertEquals(1L, s.bundlesDelivered)
        assertEquals(1L, s.bundlesAcked)
        assertEquals(1L, s.bundlesRead)
        assertEquals(1L, s.bundlesFailed)
    }

    @Test
    fun `net currently-connected clamps to zero on double-disconnect`() {
        val c = DiagnosticsCollector(recentCapacity = 8)
        c.onEvent(MeshEvent.PeerConnected("p1"))
        c.onEvent(MeshEvent.PeerDisconnected("p1"))
        c.onEvent(MeshEvent.PeerDisconnected("p1"))
        assertEquals(0L, c.peersCurrentlyConnected())
    }

    @Test
    fun `recent events retain newest when ring overflows`() {
        val c = DiagnosticsCollector(recentCapacity = 3)
        c.onEvent(MeshEvent.BundleSending("b1"))
        c.onEvent(MeshEvent.BundleSending("b2"))
        c.onEvent(MeshEvent.BundleSending("b3"))
        c.onEvent(MeshEvent.BundleSending("b4"))
        c.onEvent(MeshEvent.BundleSending("b5"))
        val events = c.recentEvents()
        assertEquals(3, events.size)
        // Oldest → newest, after two evictions.
        assertEquals("b3", events[0].detail)
        assertEquals("b4", events[1].detail)
        assertEquals("b5", events[2].detail)
    }

    @Test
    fun `reset clears counters and recent events`() {
        val c = DiagnosticsCollector(recentCapacity = 8)
        c.onEvent(MeshEvent.BundleSent("b1"))
        c.onEvent(MeshEvent.PeerConnected("p1"))
        c.reset()
        val s = c.snapshot()
        assertEquals(0L, s.bundlesSent)
        assertEquals(0L, s.peersConnected)
        assertTrue(c.recentEvents().isEmpty())
    }

    @Test
    fun `exportJson is valid JSON with expected top-level keys`() {
        var t = 1_000_000L
        val c = DiagnosticsCollector(recentCapacity = 8, now = { t })
        c.onEvent(MeshEvent.BundleSending("b1"))
        t = 1_000_100L
        c.onEvent(MeshEvent.BundleSent("b1"))

        val json = JSONObject(c.exportJson())
        assertEquals("5.1", json.getString("stage"))
        val counters = json.getJSONObject("counters")
        assertEquals(1L, counters.getLong("bundlesSending"))
        assertEquals(1L, counters.getLong("bundlesSent"))
        val events = json.getJSONArray("recentEvents")
        assertEquals(2, events.length())
        assertEquals("BundleSending", events.getJSONObject(0).getString("kind"))
        assertEquals("BundleSent", events.getJSONObject(1).getString("kind"))
    }

    @Test
    fun `snapshot uptimeMs uses injected clock`() {
        var t = 10_000L
        val c = DiagnosticsCollector(recentCapacity = 4, now = { t })
        t = 10_500L
        val s = c.snapshot()
        assertEquals(500L, s.uptimeMs)
    }
}

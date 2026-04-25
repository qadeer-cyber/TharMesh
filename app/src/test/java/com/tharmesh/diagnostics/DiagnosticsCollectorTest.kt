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
        assertEquals("5.2", json.getString("stage"))
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

    @Test
    fun `stage 5_2 record hooks each increment exactly one counter`() {
        val c = DiagnosticsCollector(recentCapacity = 32)
        c.recordRetryAttempt("b1")
        c.recordPeerChurnSuppressed("p1")
        c.recordSendRejected("p1", "b1")
        c.recordSendPaced("p1")
        c.recordTtlExpiredDrop("b2")
        c.recordStuckSendingRecovered("b3")
        val s = c.snapshot()
        assertEquals(1L, s.retryAttempts)
        assertEquals(1L, s.peerChurnEvents)
        assertEquals(1L, s.sendRejected)
        assertEquals(1L, s.sendPaced)
        assertEquals(1L, s.ttlExpiredDrops)
        assertEquals(1L, s.stuckSendingRecovered)
        // All hooks also append a recent-event entry so field testers can
        // correlate them in the rolling tail view.
        val kinds = c.recentEvents().map { it.kind }
        assertEquals(
            listOf(
                "RetryAttempt", "PeerChurnSuppressed", "SendRejected",
                "SendPaced", "TtlExpired", "StuckSendingRecovered"
            ),
            kinds
        )
    }

    @Test
    fun `stage 5_2 reset clears the new counters`() {
        val c = DiagnosticsCollector(recentCapacity = 16)
        c.recordRetryAttempt("b1")
        c.recordPeerChurnSuppressed("p1")
        c.recordSendRejected("p1", "b1")
        c.recordSendPaced("p1")
        c.recordTtlExpiredDrop("b1")
        c.recordStuckSendingRecovered("b1")
        c.reset()
        val s = c.snapshot()
        assertEquals(0L, s.retryAttempts)
        assertEquals(0L, s.peerChurnEvents)
        assertEquals(0L, s.sendRejected)
        assertEquals(0L, s.sendPaced)
        assertEquals(0L, s.ttlExpiredDrops)
        assertEquals(0L, s.stuckSendingRecovered)
    }

    @Test
    fun `exportJson exposes stage 5_2 counters`() {
        val c = DiagnosticsCollector(recentCapacity = 16, now = { 1_000_000L })
        c.recordRetryAttempt("b1")
        c.recordPeerChurnSuppressed("p1")
        c.recordSendPaced("p1")
        c.recordTtlExpiredDrop("b2")
        c.recordStuckSendingRecovered("b3")
        c.recordSendRejected("p1", "b1")
        val json = JSONObject(c.exportJson())
        val counters = json.getJSONObject("counters")
        assertEquals(1L, counters.getLong("retryAttempts"))
        assertEquals(1L, counters.getLong("peerChurnEvents"))
        assertEquals(1L, counters.getLong("sendRejected"))
        assertEquals(1L, counters.getLong("sendPaced"))
        assertEquals(1L, counters.getLong("ttlExpiredDrops"))
        assertEquals(1L, counters.getLong("stuckSendingRecovered"))
    }

    @Test
    fun `recordRelaySent rolls up totals and per-peer breakdown`() {
        val c = DiagnosticsCollector(recentCapacity = 16, now = { 2_000_000L })
        c.recordRelaySent("alice", "b1", bytes = 100)
        c.recordRelaySent("alice", "b2", bytes = 50)
        c.recordRelaySent("bob",   "b1", bytes = 200)
        // Zero / negative are ignored so counters can't go backwards.
        c.recordRelaySent("alice", "b3", bytes = 0)
        c.recordRelaySent("bob",   "b4", bytes = -5)

        val s = c.snapshot()
        assertEquals(350L, s.relayedBytesTotal)
        assertEquals(3L, s.relayForwards)

        val byPeer = c.relayedBytesByPeer()
        // Sorted descending by bytes.
        assertEquals(listOf("bob" to 200L, "alice" to 150L), byPeer)
        val forwards = c.relayForwardsByPeer()
        assertEquals(2L, forwards["alice"])
        assertEquals(1L, forwards["bob"])
    }

    @Test
    fun `exportJson surfaces per-peer relay breakdown`() {
        val c = DiagnosticsCollector(recentCapacity = 16, now = { 3_000_000L })
        c.recordRelaySent("alice", "b1", bytes = 64)
        c.recordRelaySent("bob",   "b2", bytes = 128)

        val json = JSONObject(c.exportJson())
        val counters = json.getJSONObject("counters")
        assertEquals(192L, counters.getLong("relayedBytesTotal"))
        assertEquals(2L, counters.getLong("relayForwards"))

        val perPeer = json.getJSONObject("relayedByPeer")
        assertEquals(64L, perPeer.getJSONObject("alice").getLong("bytes"))
        assertEquals(1L,  perPeer.getJSONObject("alice").getLong("forwards"))
        assertEquals(128L, perPeer.getJSONObject("bob").getLong("bytes"))
        assertEquals(1L,   perPeer.getJSONObject("bob").getLong("forwards"))
    }

    @Test
    fun `reset clears per-peer relay bookkeeping`() {
        val c = DiagnosticsCollector(recentCapacity = 16)
        c.recordRelaySent("alice", "b1", bytes = 100)
        c.recordRelaySent("bob",   "b2", bytes = 200)
        c.reset()
        assertEquals(0L, c.relayedBytesTotal.get())
        assertEquals(0L, c.relayForwards.get())
        assertTrue(c.relayedBytesByPeer().isEmpty())
        assertTrue(c.relayForwardsByPeer().isEmpty())
    }
}

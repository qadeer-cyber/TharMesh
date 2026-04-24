package com.tharmesh.mesh

import com.tharmesh.dtn.MeshEngine
import com.tharmesh.dtn.MeshEvent
import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in A1: real TransportEvents flow through MeshEngine to NearbyMeshDataSource,
 * making the Devices tab show real peers and flip ScanState appropriately.
 */
class NearbyMeshDataSourceTest {

    private class SilentTransport : Transport {
        private var listener: ((TransportEvent) -> Unit)? = null
        override fun start(localPeerId: String) {}
        override fun stop() {}
        override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean = true
        override fun setListener(listener: (TransportEvent) -> Unit) { this.listener = listener }
        fun fire(event: TransportEvent) { listener?.invoke(event) }
    }

    @Test
    fun peerFoundThenConnected_populatesNodesAndFlipsToFound() {
        val transport = SilentTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val source = NearbyMeshDataSource(engine)
        source.startScan()

        assertTrue("Empty before any peer", source.nodes.value.isEmpty())
        assertEquals(ScanState.SCANNING, source.scanState.value)

        transport.fire(TransportEvent.PeerFound(peerId = "alice-uid", displayName = "Alice"))
        assertEquals(1, source.nodes.value.size)
        val n = source.nodes.value.single()
        assertEquals("alice-uid", n.userId)
        assertEquals("Alice", n.name)
        assertFalse("Still only found, not yet connected", n.online)
        assertEquals(ScanState.FOUND, source.scanState.value)

        transport.fire(TransportEvent.PeerConnected(peerId = "alice-uid"))
        val connected = source.nodes.value.single()
        assertTrue("PeerConnected flips to online", connected.online)
    }

    @Test
    fun peerDisconnected_removesNode() {
        val transport = SilentTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val source = NearbyMeshDataSource(engine)
        source.startScan()
        transport.fire(TransportEvent.PeerFound(peerId = "alice-uid", displayName = "Alice"))
        transport.fire(TransportEvent.PeerConnected(peerId = "alice-uid"))
        assertEquals(1, source.nodes.value.size)

        transport.fire(TransportEvent.PeerDisconnected(peerId = "alice-uid"))
        assertTrue("Removed on disconnect", source.nodes.value.isEmpty())
        assertEquals("No peers while scanning -> SCANNING state",
            ScanState.SCANNING, source.scanState.value)
    }

    @Test
    fun stopScan_whileEmpty_flipsToIdle() {
        val transport = SilentTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val source = NearbyMeshDataSource(engine)
        source.startScan()
        assertEquals(ScanState.SCANNING, source.scanState.value)
        source.stopScan()
        // stopScan with empty list settles to NONE (the "scan finished, nothing found" case).
        val s = source.scanState.value
        assertTrue("stopScan with empty list settles to IDLE or NONE",
            s == ScanState.IDLE || s == ScanState.NONE)
    }

    @Test
    fun eventDrivenRetry_firesOnPeerConnected() {
        // PeerConnected hitting MeshEngine should be forwarded to the repository-facing
        // eventListener as MeshEvent.PeerConnected so the store-and-forward flush runs.
        val transport = SilentTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val events = mutableListOf<MeshEvent>()
        engine.setEventListener { events.add(it) }

        transport.fire(TransportEvent.PeerConnected(peerId = "alice-uid"))

        assertTrue("PeerConnected forwarded to repository",
            events.any { it is MeshEvent.PeerConnected && it.peerId == "alice-uid" })
    }
}

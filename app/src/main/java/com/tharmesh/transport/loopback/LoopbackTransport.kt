package com.tharmesh.transport.loopback

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process transport used for unit tests and for single-device developer sandboxes where two
 * [MeshEngine] instances live in the same JVM. Every instance registers itself in a shared [Hub];
 * `send(peerId, payload)` delivers synchronously to the peer's listener on the calling thread.
 *
 * Not suitable for production — there is zero wire, zero encryption, zero ordering guarantee
 * across threads. It exists only to let us test the [MeshEngine] + [MessageRepository] pipeline
 * without real Android Nearby Connections.
 */
class LoopbackTransport(private val hub: Hub = Hub.shared) : Transport {

    private var listener: ((TransportEvent) -> Unit)? = null
    private var localPeerId: String = ""
    private var running: Boolean = false

    override fun start(localPeerId: String) {
        this.localPeerId = localPeerId
        running = true
        hub.register(localPeerId, this)
        // Announce ourselves to every already-registered peer so both sides see PeerConnected.
        for (otherId in hub.peerIds() - localPeerId) {
            val other = hub.get(otherId) ?: continue
            other.deliverEvent(TransportEvent.PeerConnected(localPeerId))
            listener?.invoke(TransportEvent.PeerConnected(otherId))
        }
    }

    override fun stop() {
        running = false
        hub.unregister(localPeerId)
        val id = localPeerId
        if (id.isEmpty()) return
        for (otherId in hub.peerIds() - id) {
            hub.get(otherId)?.deliverEvent(TransportEvent.PeerDisconnected(id))
        }
    }

    override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
        if (!running) {
            listener?.invoke(TransportEvent.Error(peerId, sendId, "Transport not started"))
            return false
        }
        val peer = hub.get(peerId)
        if (peer == null) {
            listener?.invoke(TransportEvent.Error(peerId, sendId, "Peer not connected"))
            return false
        }
        peer.deliverEvent(TransportEvent.PayloadReceived(localPeerId, payload))
        listener?.invoke(TransportEvent.PayloadSent(peerId, sendId, payload.size))
        return true
    }

    override fun setListener(listener: (TransportEvent) -> Unit) {
        this.listener = listener
    }

    private fun deliverEvent(event: TransportEvent) {
        listener?.invoke(event)
    }

    class Hub {
        private val peers: MutableMap<String, LoopbackTransport> = ConcurrentHashMap()

        fun register(peerId: String, transport: LoopbackTransport) {
            peers[peerId] = transport
        }

        fun unregister(peerId: String) {
            peers.remove(peerId)
        }

        fun get(peerId: String): LoopbackTransport? = peers[peerId]
        fun peerIds(): Set<String> = peers.keys.toSet()
        fun clear() {
            peers.clear()
        }

        companion object {
            /** Process-wide singleton. Tests can also create isolated [Hub] instances. */
            val shared: Hub = Hub()
        }
    }
}

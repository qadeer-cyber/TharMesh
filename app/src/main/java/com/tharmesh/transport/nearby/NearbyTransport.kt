package com.tharmesh.transport.nearby

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent

class NearbyTransport : Transport {

    private var listener: ((TransportEvent) -> Unit)? = null
    private var running: Boolean = false
    private var localPeer: String = ""

    override fun start(localPeerId: String) {
        running = true
        localPeer = localPeerId
        listener?.invoke(TransportEvent.PeerFound(localPeerId, "Self"))
        listener?.invoke(TransportEvent.PeerConnected(localPeerId))
    }

    override fun stop() {
        running = false
        if (localPeer.isNotBlank()) {
            listener?.invoke(TransportEvent.PeerDisconnected(localPeer))
        }
    }

    override fun send(peerId: String, payload: ByteArray): Boolean {
        if (!running) {
            listener?.invoke(TransportEvent.Error(peerId, "Transport not started"))
            return false
        }
        listener?.invoke(TransportEvent.PayloadSent(peerId, payload.size))
        return true
    }

    override fun setListener(listener: (TransportEvent) -> Unit) {
        this.listener = listener
    }
}

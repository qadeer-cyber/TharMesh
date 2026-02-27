package com.tharmesh.transport.nearby

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent

class NearbyTransport : Transport {

    private var listener: ((TransportEvent) -> Unit)? = null
    private var running: Boolean = false

    override fun start(localPeerId: String) {
        running = true
        listener?.invoke(TransportEvent.PeerFound(localPeerId, "Self"))
    }

    override fun stop() {
        running = false
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

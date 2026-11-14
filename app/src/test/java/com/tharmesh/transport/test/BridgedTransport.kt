package com.tharmesh.transport.test

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-only topology transport: send succeeds only to registered neighbors.
 * **Simulated mesh — not physical radio evidence.**
 */
class BridgedTransport : Transport {
    val sends: MutableList<Send> = CopyOnWriteArrayList()
    data class Send(val to: String, val bytes: ByteArray, val sendId: Long)

    private var listener: ((TransportEvent) -> Unit)? = null
    var localPeerId: String = ""
        private set

    private val neighbors: MutableMap<String, BridgedTransport> = HashMap()

    override fun setListener(listener: (TransportEvent) -> Unit) {
        this.listener = listener
    }

    override fun start(localPeerId: String) {
        this.localPeerId = localPeerId
    }

    override fun stop() {
        neighbors.clear()
    }

    override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
        sends.add(Send(peerId, payload, sendId))
        val neighbor = neighbors[peerId]
        if (neighbor == null) {
            listener?.invoke(TransportEvent.Error(peerId, sendId, "not a neighbor"))
            return false
        }
        neighbor.deliver(TransportEvent.PayloadReceived(localPeerId, payload))
        listener?.invoke(TransportEvent.PayloadSent(peerId, sendId, payload.size))
        return true
    }

    fun link(other: BridgedTransport) {
        neighbors[other.localPeerId] = other
        other.neighbors[this.localPeerId] = this
        this.listener?.invoke(TransportEvent.PeerConnected(other.localPeerId))
        other.listener?.invoke(TransportEvent.PeerConnected(this.localPeerId))
    }

    fun unlink(other: BridgedTransport) {
        neighbors.remove(other.localPeerId)
        other.neighbors.remove(this.localPeerId)
        this.listener?.invoke(TransportEvent.PeerDisconnected(other.localPeerId))
        other.listener?.invoke(TransportEvent.PeerDisconnected(this.localPeerId))
    }

    fun deliver(event: TransportEvent) {
        listener?.invoke(event)
    }

    fun emit(event: TransportEvent) {
        listener?.invoke(event)
    }
}
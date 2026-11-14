package com.tharmesh.transport.test

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent

/**
 * Decorates [BridgedTransport] with deterministic loss and duplicate-send faults.
 * **JVM simulated fault injection — not physical device evidence.**
 */
class FaultInjectionTransport(
    val inner: BridgedTransport = BridgedTransport()
) : Transport {

    var dropOutboundCount: Int = 0
    var duplicateOutbound: Boolean = false

    override fun setListener(listener: (TransportEvent) -> Unit) {
        inner.setListener(listener)
    }

    override fun start(localPeerId: String) {
        inner.start(localPeerId)
    }

    override fun stop() {
        inner.stop()
    }

    override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
        if (dropOutboundCount > 0) {
            dropOutboundCount--
            inner.emit(TransportEvent.Error(peerId, sendId, "fault:drop"))
            return false
        }
        val ok = inner.send(peerId, payload, sendId)
        if (ok && duplicateOutbound) {
            inner.send(peerId, payload, sendId)
        }
        return ok
    }

    fun link(other: FaultInjectionTransport) {
        inner.link(other.inner)
    }

    fun unlink(other: FaultInjectionTransport) {
        inner.unlink(other.inner)
    }
}
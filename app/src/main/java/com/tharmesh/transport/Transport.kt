package com.tharmesh.transport

/**
 * A link-layer abstraction. Implementations include:
 *  - [com.tharmesh.transport.loopback.LoopbackTransport] — in-process, used by unit tests.
 *  - [com.tharmesh.transport.nearby.NearbyConnectionsTransport] — Google Play Services Nearby.
 */
interface Transport {
    fun start(localPeerId: String)
    fun stop()
    fun send(peerId: String, payload: ByteArray): Boolean
    fun setListener(listener: (TransportEvent) -> Unit)
}

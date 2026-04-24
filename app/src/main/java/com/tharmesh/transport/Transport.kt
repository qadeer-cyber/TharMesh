package com.tharmesh.transport

/**
 * A link-layer abstraction. Implementations include:
 *  - [com.tharmesh.transport.loopback.LoopbackTransport] — in-process, used by unit tests.
 *  - [com.tharmesh.transport.nearby.NearbyConnectionsTransport] — Google Play Services Nearby.
 *
 * `send` is **best-effort, async by contract**: the returned Boolean only indicates
 * whether the send was *accepted* (endpoint known, transport running). A successful
 * transmit is reported later via [TransportEvent.PayloadSent] with the same [sendId]
 * that was supplied. A failure is reported via [TransportEvent.Error] with the same
 * [sendId]. Callers that care about correlation (e.g. `MeshEngine` tagging BUNDLE
 * frames) must track [sendId] they pass in.
 */
interface Transport {
    fun start(localPeerId: String)
    fun stop()

    /**
     * Queue [payload] for delivery to [peerId]. Returns true if the transport accepted
     * the send (endpoint known + transport running). A true return DOES NOT imply the
     * bytes are on the wire yet — the authoritative signal is [TransportEvent.PayloadSent]
     * (success) or [TransportEvent.Error] (failure), both carrying the same [sendId].
     */
    fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean

    fun setListener(listener: (TransportEvent) -> Unit)
}

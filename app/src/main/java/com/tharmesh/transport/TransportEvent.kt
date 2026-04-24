package com.tharmesh.transport

sealed class TransportEvent {
    data class PeerFound(val peerId: String, val displayName: String) : TransportEvent()
    data class PeerConnected(val peerId: String) : TransportEvent()
    data class PeerDisconnected(val peerId: String) : TransportEvent()
    data class PayloadReceived(val peerId: String, val bytes: ByteArray) : TransportEvent()

    /**
     * Fired when a payload submitted via [Transport.send] has actually been handed to the
     * link layer successfully. [sendId] matches the id passed into `send`.
     */
    data class PayloadSent(val peerId: String, val sendId: Long, val bytesCount: Int) : TransportEvent()

    /**
     * Fired on an outbound send failure. [sendId] is 0 when the failure is not correlated
     * to a specific send (e.g. startAdvertising failed, peer connection failed).
     */
    data class Error(val peerId: String?, val sendId: Long, val reason: String) : TransportEvent()
}

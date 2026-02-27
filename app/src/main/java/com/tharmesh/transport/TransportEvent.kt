package com.tharmesh.transport

sealed class TransportEvent {
    data class PeerFound(val peerId: String, val displayName: String) : TransportEvent()
    data class PeerConnected(val peerId: String) : TransportEvent()
    data class PeerDisconnected(val peerId: String) : TransportEvent()
    data class PayloadReceived(val peerId: String, val bytes: ByteArray) : TransportEvent()
    data class PayloadSent(val peerId: String, val bytesCount: Int) : TransportEvent()
    data class Error(val peerId: String?, val reason: String) : TransportEvent()
}

package com.tharmesh.transport

interface Transport {
    fun start(localPeerId: String)
    fun stop()
    fun send(peerId: String, payload: ByteArray): Boolean
    fun setListener(listener: (TransportEvent) -> Unit)
}

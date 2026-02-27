package com.tharmesh.dtn

enum class ProtocolType {
    HELLO,
    INV,
    GET,
    BUNDLE,
    ACK
}

data class ProtocolFrame(
    val type: ProtocolType,
    val fromPeerId: String,
    val payload: String
)

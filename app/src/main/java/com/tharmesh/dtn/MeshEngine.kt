package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.UUID

class MeshEngine(
    private val localUserId: String,
    private val transport: Transport,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val router = Router()
    private val cache: MutableMap<String, MeshBundle> = linkedMapOf()
    private var onBundleDelivered: ((MeshBundle) -> Unit)? = null

    init {
        transport.setListener { event: TransportEvent ->
            onTransportEvent(event)
        }
    }

    fun start() {
        transport.start(localUserId)
    }

    fun stop() {
        transport.stop()
    }

    fun setBundleListener(listener: (MeshBundle) -> Unit) {
        onBundleDelivered = listener
    }

    fun queueText(destId: String, payloadCiphertext: String, ttlMs: Long, hops: Int): MeshBundle {
        val bundle = MeshBundle(
            bundleId = UUID.randomUUID().toString(),
            srcId = localUserId,
            destId = destId,
            payloadCiphertext = payloadCiphertext,
            ttlUntil = now() + ttlMs,
            hopsLeft = hops,
            signature = "TODO_SIG",
            status = "PENDING"
        )
        cache[bundle.bundleId] = bundle
        return bundle
    }

    fun syncWithPeer(peerId: String) {
        val frame = ProtocolFrame(ProtocolType.INV, localUserId, ProtocolCodec.encodeInv(InvPacket(cache.keys.toList())))
        transport.send(peerId, ProtocolCodec.encode(frame))
    }

    private fun onTransportEvent(event: TransportEvent) {
        if (event !is TransportEvent.PayloadReceived) {
            return
        }
        val frame = ProtocolCodec.decode(event.bytes) ?: return
        when (frame.type) {
            ProtocolType.INV -> handleInv(event.peerId, frame.payload)
            ProtocolType.GET -> handleGet(event.peerId, frame.payload)
            ProtocolType.BUNDLE -> handleBundle(event.peerId, frame.payload)
            ProtocolType.ACK -> handleAck(frame.payload)
            ProtocolType.HELLO -> {
                // TODO: Use for trust handshake and capability exchange.
            }
        }
    }

    private fun handleInv(peerId: String, payload: String) {
        val peerIds = ProtocolCodec.decodeIds(payload)
        val missing = peerIds.filter { id: String -> !cache.containsKey(id) }
        if (missing.isEmpty()) {
            return
        }
        val frame = ProtocolFrame(ProtocolType.GET, localUserId, ProtocolCodec.encodeGet(GetPacket(missing.take(50))))
        transport.send(peerId, ProtocolCodec.encode(frame))
    }

    private fun handleGet(peerId: String, payload: String) {
        val requested = ProtocolCodec.decodeIds(payload)
        for (id in requested) {
            val bundle = cache[id] ?: continue
            if (!router.shouldForward(bundle, peerId, now())) {
                continue
            }
            val next = bundle.copy(hopsLeft = bundle.hopsLeft - 1, status = "FORWARDED")
            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(next))
            transport.send(peerId, ProtocolCodec.encode(frame))
        }
    }

    private fun handleBundle(peerId: String, payload: String) {
        val bundle = BundleCodec.decode(payload) ?: return
        if (!cache.containsKey(bundle.bundleId)) {
            cache[bundle.bundleId] = bundle
        }
        if (bundle.destId == localUserId) {
            onBundleDelivered?.invoke(bundle.copy(status = "DELIVERED_FINAL"))
            val ackPacket = AckPacket(bundle.bundleId, bundle.srcId, now(), "TODO_ACK_SIG")
            val ack = ProtocolFrame(ProtocolType.ACK, localUserId, ProtocolCodec.encodeAck(ackPacket))
            transport.send(peerId, ProtocolCodec.encode(ack))
        }
    }

    private fun handleAck(payload: String) {
        val ack = ProtocolCodec.decodeAck(payload) ?: return
        val existing = cache[ack.bundleId] ?: return
        cache[ack.bundleId] = existing.copy(status = "DELIVERED_FINAL")
    }

}

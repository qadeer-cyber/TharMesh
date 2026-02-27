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
        val inv = BundleCodec.encodeInventory(cache.keys.toList())
        val frame = ProtocolFrame(ProtocolType.INV, localUserId, inv)
        transport.send(peerId, encodeFrame(frame))
    }

    private fun onTransportEvent(event: TransportEvent) {
        if (event !is TransportEvent.PayloadReceived) {
            return
        }
        val frame = decodeFrame(event.bytes) ?: return
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
        val peerIds = BundleCodec.decodeInventory(payload)
        val missing = peerIds.filter { id: String -> !cache.containsKey(id) }
        if (missing.isEmpty()) {
            return
        }
        val frame = ProtocolFrame(ProtocolType.GET, localUserId, BundleCodec.encodeInventory(missing))
        transport.send(peerId, encodeFrame(frame))
    }

    private fun handleGet(peerId: String, payload: String) {
        val requested = BundleCodec.decodeInventory(payload)
        for (id in requested) {
            val bundle = cache[id] ?: continue
            if (!router.shouldForward(bundle, peerId, now())) {
                continue
            }
            val next = bundle.copy(hopsLeft = bundle.hopsLeft - 1, status = "FORWARDED")
            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(next))
            transport.send(peerId, encodeFrame(frame))
        }
    }

    private fun handleBundle(peerId: String, payload: String) {
        val bundle = BundleCodec.decode(payload) ?: return
        if (!cache.containsKey(bundle.bundleId)) {
            cache[bundle.bundleId] = bundle
        }
        if (bundle.destId == localUserId) {
            onBundleDelivered?.invoke(bundle.copy(status = "DELIVERED_FINAL"))
            val ack = ProtocolFrame(ProtocolType.ACK, localUserId, bundle.bundleId)
            transport.send(peerId, encodeFrame(ack))
        }
    }

    private fun handleAck(bundleId: String) {
        val existing = cache[bundleId] ?: return
        cache[bundleId] = existing.copy(status = "DELIVERED_FINAL")
    }

    private fun encodeFrame(frame: ProtocolFrame): ByteArray {
        val wire = frame.type.name + "|" + frame.fromPeerId + "|" + frame.payload
        return wire.toByteArray(Charsets.UTF_8)
    }

    private fun decodeFrame(bytes: ByteArray): ProtocolFrame? {
        return try {
            val raw = String(bytes, Charsets.UTF_8)
            val first = raw.indexOf('|')
            val second = raw.indexOf('|', first + 1)
            if (first <= 0 || second <= first) {
                return null
            }
            val type = ProtocolType.valueOf(raw.substring(0, first))
            val from = raw.substring(first + 1, second)
            val payload = raw.substring(second + 1)
            ProtocolFrame(type, from, payload)
        } catch (ignored: Throwable) {
            null
        }
    }
}

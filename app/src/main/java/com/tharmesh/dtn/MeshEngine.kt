package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.UUID

/**
 * DTN (delay-tolerant networking) engine. Owns the bundle cache and the on-wire protocol.
 *
 * Lifecycle events that the repository layer cares about are exposed via [setEventListener].
 * The engine does not know about Room, encryption, or the UI.
 */
class MeshEngine(
    private val localUserId: String,
    private val transport: Transport,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val router = Router()
    private val cache: MutableMap<String, MeshBundle> = linkedMapOf()
    private var eventListener: ((MeshEvent) -> Unit)? = null

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

    fun setEventListener(listener: (MeshEvent) -> Unit) {
        eventListener = listener
    }

    /**
     * Queue outbound text. [originHint] is optional — if non-null it seeds [MeshBundle.bundleId],
     * which lets the repository map ACKs back to a local [MessageEntity] row.
     */
    fun queueText(
        destId: String,
        payloadCiphertext: String,
        ttlMs: Long,
        hops: Int,
        bundleIdHint: String? = null
    ): MeshBundle {
        val bundleId = bundleIdHint ?: UUID.randomUUID().toString()
        val bundle = MeshBundle(
            bundleId = bundleId,
            srcId = localUserId,
            destId = destId,
            payloadCiphertext = payloadCiphertext,
            ttlUntil = now() + ttlMs,
            hopsLeft = hops,
            signature = "TODO_SIG",
            status = "PENDING"
        )
        cache[bundle.bundleId] = bundle
        // Try to send immediately to every connected peer; routing decides if we actually do.
        broadcastBundle(bundle)
        return bundle
    }

    /**
     * Emit a READ receipt for a given bundleId. Best-effort: if no transport peer is connected
     * it just queues the frame in memory (lost on process death — acceptable for MVP).
     */
    fun sendRead(bundleId: String, toPeerId: String) {
        val frame = ProtocolFrame(ProtocolType.READ, localUserId, bundleId)
        transport.send(toPeerId, encodeFrame(frame))
    }

    fun syncWithPeer(peerId: String) {
        val inv = BundleCodec.encodeInventory(cache.keys.toList())
        val frame = ProtocolFrame(ProtocolType.INV, localUserId, inv)
        transport.send(peerId, encodeFrame(frame))
    }

    private fun broadcastBundle(bundle: MeshBundle) {
        val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
        // Naive: try destId directly — real Nearby transport resolves userId → endpoint.
        transport.send(bundle.destId, encodeFrame(frame))
    }

    private fun onTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PayloadReceived -> {
                val frame = decodeFrame(event.bytes) ?: return
                when (frame.type) {
                    ProtocolType.INV -> handleInv(event.peerId, frame.payload)
                    ProtocolType.GET -> handleGet(event.peerId, frame.payload)
                    ProtocolType.BUNDLE -> handleBundle(event.peerId, frame.payload)
                    ProtocolType.ACK -> handleAck(frame.fromPeerId, frame.payload)
                    ProtocolType.READ -> handleRead(frame.fromPeerId, frame.payload)
                    ProtocolType.HELLO -> {
                        // TODO: trust handshake + capability exchange.
                    }
                }
            }
            is TransportEvent.PayloadSent -> {
                // Best-effort: treat successful link-layer send as SENT for every bundle that
                // was broadcast in this instant. We reconstruct bundleId from the payload bytes
                // because the low-level transport doesn't preserve semantic metadata.
                val bundleId = sniffBundleIdFrom(event)
                if (bundleId != null) {
                    val bundle = cache[bundleId]
                    if (bundle != null && bundle.srcId == localUserId) {
                        eventListener?.invoke(MeshEvent.BundleSent(bundleId))
                    }
                }
            }
            is TransportEvent.PeerConnected -> {
                syncWithPeer(event.peerId)
            }
            is TransportEvent.PeerFound,
            is TransportEvent.PeerDisconnected,
            is TransportEvent.Error -> {
                // Not interesting for the repository layer yet.
            }
        }
    }

    private fun sniffBundleIdFrom(event: TransportEvent.PayloadSent): String? {
        // PayloadSent only carries byte count in the current Transport contract; a richer
        // contract would carry the bundleId. For the loopback transport we rely on the
        // sender's own BUNDLE frame always using the format "BUNDLE|<from>|<json>" and
        // parse it from the in-flight cache instead.
        val candidates = cache.values.filter { it.srcId == localUserId && it.destId == event.peerId }
        return candidates.maxByOrNull { it.ttlUntil }?.bundleId
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
            val delivered = bundle.copy(status = "DELIVERED_FINAL")
            cache[delivered.bundleId] = delivered
            eventListener?.invoke(MeshEvent.BundleDelivered(delivered))
            val ack = ProtocolFrame(ProtocolType.ACK, localUserId, bundle.bundleId)
            transport.send(peerId, encodeFrame(ack))
        }
    }

    private fun handleAck(ackedBy: String, bundleId: String) {
        val existing = cache[bundleId] ?: return
        if (existing.srcId != localUserId) return
        cache[bundleId] = existing.copy(status = "DELIVERED_FINAL")
        eventListener?.invoke(MeshEvent.BundleAcked(bundleId, ackedBy))
    }

    private fun handleRead(readBy: String, bundleId: String) {
        val existing = cache[bundleId] ?: return
        if (existing.srcId != localUserId) return
        eventListener?.invoke(MeshEvent.BundleRead(bundleId, readBy))
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

sealed class MeshEvent {
    data class BundleSent(val bundleId: String) : MeshEvent()
    data class BundleAcked(val bundleId: String, val ackedByUserId: String) : MeshEvent()
    data class BundleRead(val bundleId: String, val readByUserId: String) : MeshEvent()
    data class BundleDelivered(val bundle: MeshBundle) : MeshEvent()
}

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
    // Bundle cache is read/written from both IO dispatcher threads (repository sends, retry
    // loop) and Google Nearby Connections callback threads (handleBundle/handleAck/handleRead).
    // LinkedHashMap is NOT thread-safe; wrap every access in [cacheLock] below. We keep
    // LinkedHashMap to preserve insertion order for inventory-sync determinism.
    private val cacheLock = Any()
    private val cache: MutableMap<String, MeshBundle> = linkedMapOf()
    @Volatile private var eventListener: ((MeshEvent) -> Unit)? = null

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
        synchronized(cacheLock) { cache[bundle.bundleId] = bundle }
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

    /**
     * Re-broadcast a cached outbound bundle. Called by the repository's store-and-forward
     * retry loop. If the bundle is no longer in the cache (unlikely) or was already marked
     * delivered this is a no-op.
     */
    fun retryBundle(bundleId: String) {
        val bundle = synchronized(cacheLock) { cache[bundleId] } ?: return
        if (bundle.srcId != localUserId) return
        if (bundle.status == "DELIVERED_FINAL") return
        broadcastBundle(bundle)
    }

    fun syncWithPeer(peerId: String) {
        val snapshot = synchronized(cacheLock) { cache.keys.toList() }
        val inv = BundleCodec.encodeInventory(snapshot)
        val frame = ProtocolFrame(ProtocolType.INV, localUserId, inv)
        transport.send(peerId, encodeFrame(frame))
    }

    private fun broadcastBundle(bundle: MeshBundle) {
        val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
        // Naive: try destId directly — real Nearby transport resolves userId → endpoint.
        val ok = transport.send(bundle.destId, encodeFrame(frame))
        // We emit BundleSent directly here rather than inferring it from PayloadSent —
        // otherwise non-BUNDLE frames (INV/GET/ACK/READ) sent to the same peer would
        // prematurely flip our outbound message to SENT.
        if (ok && bundle.srcId == localUserId) {
            eventListener?.invoke(MeshEvent.BundleSent(bundle.bundleId))
        }
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
                // BundleSent is now emitted directly from [broadcastBundle] on a successful
                // transport.send — we don't sniff PayloadSent events here because the
                // low-level byte count doesn't let us tell a BUNDLE from an INV/ACK/READ.
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

    private fun handleInv(peerId: String, payload: String) {
        val peerIds = BundleCodec.decodeInventory(payload)
        val missing = synchronized(cacheLock) {
            peerIds.filter { id: String -> !cache.containsKey(id) }
        }
        if (missing.isEmpty()) {
            return
        }
        val frame = ProtocolFrame(ProtocolType.GET, localUserId, BundleCodec.encodeInventory(missing))
        transport.send(peerId, encodeFrame(frame))
    }

    private fun handleGet(peerId: String, payload: String) {
        val requested = BundleCodec.decodeInventory(payload)
        val toForward: List<MeshBundle> = synchronized(cacheLock) {
            requested.mapNotNull { id -> cache[id] }
        }
        for (bundle in toForward) {
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
        var alreadyDelivered = false
        var deliveredSnapshot: MeshBundle? = null
        synchronized(cacheLock) {
            if (!cache.containsKey(bundle.bundleId)) {
                cache[bundle.bundleId] = bundle
            }
            if (bundle.destId == localUserId) {
                alreadyDelivered = cache[bundle.bundleId]?.status == "DELIVERED_FINAL"
                val delivered = bundle.copy(status = "DELIVERED_FINAL")
                cache[delivered.bundleId] = delivered
                deliveredSnapshot = delivered
            }
        }
        if (deliveredSnapshot != null) {
            // Relay paths can deliver the same bundle more than once. Only emit
            // BundleDelivered on the first arrival; subsequent copies still ACK so
            // the sender can retire its queue but do not re-notify the repository.
            if (!alreadyDelivered) {
                eventListener?.invoke(MeshEvent.BundleDelivered(deliveredSnapshot!!))
            }
            val ack = ProtocolFrame(ProtocolType.ACK, localUserId, bundle.bundleId)
            transport.send(peerId, encodeFrame(ack))
        }
    }

    private fun handleAck(ackedBy: String, bundleId: String) {
        val updated: Boolean = synchronized(cacheLock) {
            val existing = cache[bundleId] ?: return@synchronized false
            if (existing.srcId != localUserId) return@synchronized false
            cache[bundleId] = existing.copy(status = "DELIVERED_FINAL")
            true
        }
        if (updated) eventListener?.invoke(MeshEvent.BundleAcked(bundleId, ackedBy))
    }

    private fun handleRead(readBy: String, bundleId: String) {
        val fireEvent: Boolean = synchronized(cacheLock) {
            val existing = cache[bundleId] ?: return@synchronized false
            existing.srcId == localUserId
        }
        if (fireEvent) eventListener?.invoke(MeshEvent.BundleRead(bundleId, readBy))
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

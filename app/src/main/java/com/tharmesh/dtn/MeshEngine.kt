package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * DTN (delay-tolerant networking) engine. Owns the bundle cache and the on-wire protocol.
 *
 * Lifecycle events that the repository layer cares about are exposed via [setEventListener].
 * The engine does not know about Room, encryption, or the UI.
 */
class MeshEngine(
    private val localUserId: String,
    private val transport: Transport,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxCacheSize: Int = DEFAULT_MAX_CACHE_SIZE
) {

    private val router = Router()
    // Bundle cache is read/written from both IO dispatcher threads (repository sends, retry
    // loop) and Google Nearby Connections callback threads (handleBundle/handleAck/handleRead).
    // LinkedHashMap is NOT thread-safe; wrap every access in [cacheLock] below. We keep
    // LinkedHashMap to preserve insertion order for inventory-sync determinism AND LRU
    // eviction when we exceed [maxCacheSize].
    private val cacheLock = Any()
    private val cache: MutableMap<String, MeshBundle> = linkedMapOf()
    @Volatile private var eventListener: ((MeshEvent) -> Unit)? = null

    // Additional peer-lifecycle listeners — the data source that feeds the Devices UI
    // subscribes here so it can update its StateFlow as TransportEvents arrive.
    // Copy-on-write list so fire paths don't need to hold a lock.
    @Volatile private var peerListeners: List<(MeshEvent) -> Unit> = emptyList()
    private val peerListenersLock = Any()

    // sendId → bundleId, for correlating PayloadSent / Error callbacks back to the BUNDLE
    // they acknowledge. Non-BUNDLE sends (INV/GET/ACK/READ) do not populate this map —
    // their PayloadSent events are ignored for BundleSent purposes.
    private val pendingLock = Any()
    private val pendingBundleSends: MutableMap<Long, String> = HashMap()
    private val sendIdSeq = AtomicLong(1L)

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
     * Subscribe to peer-lifecycle [MeshEvent.PeerFound] / [MeshEvent.PeerConnected] /
     * [MeshEvent.PeerDisconnected] (the data source uses this to keep the Devices tab
     * in sync with the real transport). These listeners are additive and coexist with
     * the single [setEventListener] used by the repository.
     */
    fun addPeerListener(listener: (MeshEvent) -> Unit) {
        synchronized(peerListenersLock) {
            peerListeners = peerListeners + listener
        }
    }

    fun removePeerListener(listener: (MeshEvent) -> Unit) {
        synchronized(peerListenersLock) {
            peerListeners = peerListeners - listener
        }
    }

    private fun firePeerEvent(event: MeshEvent) {
        val snapshot = peerListeners
        for (l in snapshot) {
            try {
                l(event)
            } catch (_: Throwable) {
                // Listener failures must not break the mesh engine.
            }
        }
    }

    /**
     * Queue outbound text. [bundleIdHint] is optional — if non-null it seeds [MeshBundle.bundleId],
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
            hopsLeft = hops.coerceAtLeast(0),
            signature = "TODO_SIG",
            status = "PENDING"
        )
        cachePut(bundle)
        // Try to send immediately to every connected peer; routing decides if we actually do.
        broadcastBundle(bundle)
        return bundle
    }

    /** Non-bundle frame send — no BundleSent correlation needed. */
    private fun sendFrame(peerId: String, frame: ProtocolFrame) {
        transport.send(peerId, encodeFrame(frame), sendId = 0L)
    }

    /**
     * Emit a READ receipt for a given bundleId. Best-effort: if no transport peer is connected
     * it just queues the frame in memory (lost on process death — acceptable for MVP).
     */
    fun sendRead(bundleId: String, toPeerId: String) {
        sendFrame(toPeerId, ProtocolFrame(ProtocolType.READ, localUserId, bundleId))
    }

    /**
     * Re-broadcast a cached outbound bundle. Called by the repository's store-and-forward
     * retry loop and opportunistically on PeerConnected. If the bundle is no longer in the
     * cache, already marked delivered, or expired, this is a no-op.
     */
    fun retryBundle(bundleId: String) {
        val bundle = synchronized(cacheLock) { cache[bundleId] } ?: return
        if (bundle.srcId != localUserId) return
        if (bundle.status == "DELIVERED_FINAL") return
        if (bundle.ttlUntil < now()) return
        broadcastBundle(bundle)
    }

    /**
     * Called by the repository on PeerConnected — re-broadcasts all of our own cached
     * outbound bundles that are not yet DELIVERED, so the new peer gets them immediately
     * instead of waiting for the retry timer.
     */
    fun retryAllPendingForLocalUser() {
        val snapshot = synchronized(cacheLock) {
            cache.values.filter { it.srcId == localUserId && it.status != "DELIVERED_FINAL" }.toList()
        }
        val nowMs = now()
        for (bundle in snapshot) {
            if (bundle.ttlUntil < nowMs) continue
            broadcastBundle(bundle)
        }
    }

    fun syncWithPeer(peerId: String) {
        val snapshot = synchronized(cacheLock) { cache.keys.toList() }
        val inv = BundleCodec.encodeInventory(snapshot)
        sendFrame(peerId, ProtocolFrame(ProtocolType.INV, localUserId, inv))
    }

    private fun broadcastBundle(bundle: MeshBundle) {
        if (bundle.ttlUntil < now()) return
        val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
        val sendId = sendIdSeq.getAndIncrement()
        // Track the correlation BEFORE calling send — a synchronous failure path inside
        // send (e.g. LoopbackTransport "Peer not connected") will fire Error(sendId)
        // synchronously and pop from the map.
        synchronized(pendingLock) { pendingBundleSends[sendId] = bundle.bundleId }
        val accepted = transport.send(bundle.destId, encodeFrame(frame), sendId)
        if (!accepted) {
            // Defensive: if the transport returned false AND didn't fire Error synchronously
            // for some reason, release the correlation so we don't leak.
            synchronized(pendingLock) { pendingBundleSends.remove(sendId) }
        }
        // NB: BundleSent is NOT emitted here. The repository only hears about it when
        // TransportEvent.PayloadSent fires back with our sendId (see onTransportEvent).
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
                val bundleId = synchronized(pendingLock) { pendingBundleSends.remove(event.sendId) }
                if (bundleId != null) {
                    eventListener?.invoke(MeshEvent.BundleSent(bundleId))
                }
                // sendId == 0 or non-BUNDLE send → ignore; non-bundle frames don't surface as
                // message-level BundleSent events.
            }
            is TransportEvent.PeerConnected -> {
                // Opportunistic inventory sync with the new peer.
                syncWithPeer(event.peerId)
                val e = MeshEvent.PeerConnected(event.peerId)
                firePeerEvent(e)
                eventListener?.invoke(e)
            }
            is TransportEvent.PeerFound -> {
                firePeerEvent(MeshEvent.PeerFound(event.peerId, event.displayName))
            }
            is TransportEvent.PeerDisconnected -> {
                firePeerEvent(MeshEvent.PeerDisconnected(event.peerId))
            }
            is TransportEvent.Error -> {
                val bundleId = synchronized(pendingLock) { pendingBundleSends.remove(event.sendId) }
                if (bundleId != null) {
                    eventListener?.invoke(MeshEvent.BundleFailed(bundleId, event.reason))
                }
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
        sendFrame(peerId, ProtocolFrame(ProtocolType.GET, localUserId, BundleCodec.encodeInventory(missing)))
    }

    private fun handleGet(peerId: String, payload: String) {
        val requested = BundleCodec.decodeInventory(payload)
        val nowMs = now()
        val toForward: List<MeshBundle> = synchronized(cacheLock) {
            requested.mapNotNull { id -> cache[id] }
        }
        for (bundle in toForward) {
            if (bundle.ttlUntil < nowMs) continue
            if (bundle.hopsLeft <= 0) continue
            if (!router.shouldForward(bundle, peerId, nowMs)) continue
            val nextHops = (bundle.hopsLeft - 1).coerceAtLeast(0)
            val next = bundle.copy(hopsLeft = nextHops, status = "FORWARDED")
            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(next))
            sendFrame(peerId, frame)
        }
    }

    private fun handleBundle(peerId: String, payload: String) {
        val bundle = BundleCodec.decode(payload) ?: return
        val nowMs = now()
        // Drop expired / hop-exhausted at receive time too — we don't want to cache them,
        // we don't want to ACK them, we don't want to forward them.
        if (bundle.ttlUntil < nowMs) return
        if (bundle.hopsLeft < 0) return

        var alreadyDelivered = false
        var deliveredSnapshot: MeshBundle? = null
        synchronized(cacheLock) {
            if (!cache.containsKey(bundle.bundleId)) {
                cachePutLocked(bundle)
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
            sendFrame(peerId, ack)
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

    /**
     * Insert-or-update a bundle in the cache with LRU eviction when we exceed [maxCacheSize].
     * Public-ish only for tests; production paths use this via queueText/handleBundle.
     */
    internal fun cachePut(bundle: MeshBundle) {
        synchronized(cacheLock) { cachePutLocked(bundle) }
    }

    internal fun cacheSize(): Int = synchronized(cacheLock) { cache.size }

    private fun cachePutLocked(bundle: MeshBundle) {
        // LinkedHashMap keeps insertion order; remove-then-put refreshes "recency".
        cache.remove(bundle.bundleId)
        cache[bundle.bundleId] = bundle
        if (cache.size <= maxCacheSize) return

        // Enforce the stated invariant: never evict a bundle that is (a) currently
        // awaiting PayloadSent/Error correlation, or (b) a local unDELIVERED outbound
        // — the store-and-forward retry loop still needs it. Snapshot the pending set
        // while holding pendingLock, then walk the LinkedHashMap (oldest-first) and
        // remove the first evictable entry. If nothing is evictable (every cache entry
        // is protected), stop — we'd rather exceed the cap briefly than drop pending
        // traffic on the floor.
        val pendingBundleIds = synchronized(pendingLock) { pendingBundleSends.values.toSet() }
        while (cache.size > maxCacheSize) {
            val iter = cache.entries.iterator()
            var evicted = false
            while (iter.hasNext()) {
                val entry = iter.next()
                val key = entry.key
                if (key == bundle.bundleId) continue
                if (key in pendingBundleIds) continue
                val v = entry.value
                val isLocalUndelivered =
                    v.srcId == localUserId && v.status != "DELIVERED_FINAL"
                if (isLocalUndelivered) continue
                iter.remove()
                evicted = true
                break
            }
            if (!evicted) break
        }
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

    companion object {
        /** Hard cap on the in-memory bundle cache (bundles, not bytes). */
        const val DEFAULT_MAX_CACHE_SIZE: Int = 500
    }
}

sealed class MeshEvent {
    data class BundleSent(val bundleId: String) : MeshEvent()
    data class BundleAcked(val bundleId: String, val ackedByUserId: String) : MeshEvent()
    data class BundleRead(val bundleId: String, val readByUserId: String) : MeshEvent()
    data class BundleDelivered(val bundle: MeshBundle) : MeshEvent()

    /** Transmit failed at the link layer. Repository flips the row to FAILED. */
    data class BundleFailed(val bundleId: String, val reason: String) : MeshEvent()

    /** A peer has been discovered (radio range). Not yet connected. */
    data class PeerFound(val peerId: String, val displayName: String) : MeshEvent()

    /** A peer connection has been established. Safe to send to [peerId] now. */
    data class PeerConnected(val peerId: String) : MeshEvent()

    /** A peer connection has been lost. Any further sends to [peerId] will fail. */
    data class PeerDisconnected(val peerId: String) : MeshEvent()
}

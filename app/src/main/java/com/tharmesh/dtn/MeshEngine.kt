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

    // bundleId → set of peerIds that have sent us this bundle. Used to implement the
    // anti-sender invariant: never forward a bundle back to a peer it arrived from.
    // Cleared alongside cache eviction (same lock).
    private val receivedFrom: MutableMap<String, MutableSet<String>> = HashMap()

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

    // Set of peerIds currently connected at the transport layer. Maintained from
    // TransportEvent.PeerConnected / PeerDisconnected. Used by the fanout paths
    // (origination + relay forwarding) so we can flood a bundle to every reachable
    // peer without asking the transport to enumerate endpoints.
    private val peersLock = Any()
    private val connectedPeers: MutableSet<String> = HashSet()

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

    /**
     * Origination fanout: flood [bundle] to every currently-connected peer. The mesh
     * decides routing — we do not assume direct connectivity to [bundle.destId]. Every
     * peer that receives the bundle will either (a) deliver-to-local + ACK if it IS
     * [bundle.destId], or (b) cache + forward (see [handleBundle] relay path).
     *
     * Emits [MeshEvent.BundleSending] once if at least one peer accepted the payload.
     * [MeshEvent.BundleSent] follows per-peer on [TransportEvent.PayloadSent] — the
     * repository's rank-protected advance makes repeat emissions idempotent.
     *
     * If no peers are connected, the bundle stays cached and the store-and-forward
     * retry loop / PeerConnected flush will re-invoke this later.
     */
    private fun broadcastBundle(bundle: MeshBundle) {
        val nowMs = now()
        if (bundle.ttlUntil < nowMs) {
            MeshLog.droppedTtl(bundle.bundleId, fromPeer = "self")
            return
        }
        val peers = snapshotConnectedPeers()
        if (peers.isEmpty()) {
            MeshLog.noConnectedPeers(bundle.bundleId)
            return
        }
        val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
        val payloadBytes = encodeFrame(frame)
        var anyAccepted = false
        for (peer in peers) {
            // Router.shouldForward is the anti-storm memo for RELAY forwarding (one send
            // per (bundleId, peer) pair). Origination / retry from the local user is
            // not bounded by the memo — the store-and-forward retry loop must be free
            // to re-broadcast after a PeerDisconnected + PeerConnected bounce.
            val sendId = sendIdSeq.getAndIncrement()
            // Track the correlation BEFORE calling send — a synchronous failure path inside
            // send (e.g. LoopbackTransport "Peer not connected") will fire Error(sendId)
            // synchronously and pop from the map.
            synchronized(pendingLock) { pendingBundleSends[sendId] = bundle.bundleId }
            val accepted = transport.send(peer, payloadBytes, sendId)
            if (!accepted) {
                synchronized(pendingLock) { pendingBundleSends.remove(sendId) }
                continue
            }
            MeshLog.sending(bundle.bundleId, peer)
            anyAccepted = true
        }
        if (anyAccepted) {
            // Repository advances QUEUED → SENDING. Actual "bytes on wire" confirmation
            // still comes later per-peer via TransportEvent.PayloadSent, at which point
            // BundleSent (→ status=SENT) is emitted in onTransportEvent.
            eventListener?.invoke(MeshEvent.BundleSending(bundle.bundleId))
        }
    }

    /**
     * Relay forwarding: a bundle arrived from [fromPeerId] that is NOT addressed to us,
     * has positive hopsLeft after decrement, and has not expired. Re-broadcast it to
     * every OTHER connected peer — never back to [fromPeerId], never to a peer that has
     * already sent us this same bundle (anti-ping-pong), and always respecting
     * [Router.shouldForward] for the (bundleId, peerId) memo.
     */
    private fun forwardBundle(bundle: MeshBundle, fromPeerId: String) {
        val nowMs = now()
        if (bundle.ttlUntil < nowMs) {
            MeshLog.droppedTtl(bundle.bundleId, fromPeer = fromPeerId)
            return
        }
        // Spec: "IF hopsLeft <= 0 → DO NOT forward". A bundle with current hopsLeft > 0
        // is still forwardable exactly once — decrement, emit, and the next hop
        // receives it with hopsLeft-1 (which may itself be 0, meaning that hop can
        // deliver-to-self but can no longer relay further).
        if (bundle.hopsLeft <= 0) {
            MeshLog.droppedHops(bundle.bundleId, fromPeerId, hopsLeft = bundle.hopsLeft)
            return
        }
        val nextHops = bundle.hopsLeft - 1
        val forwarded = bundle.copy(hopsLeft = nextHops, status = "FORWARDED")
        val peers = snapshotConnectedPeers()
        val seenFrom: Set<String> = synchronized(cacheLock) {
            receivedFrom[bundle.bundleId]?.toSet() ?: emptySet()
        }
        val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(forwarded))
        val payloadBytes = encodeFrame(frame)
        for (peer in peers) {
            if (peer == fromPeerId) {
                MeshLog.skippedAntiSender(bundle.bundleId, peer)
                continue
            }
            if (peer in seenFrom) {
                MeshLog.skippedAntiSender(bundle.bundleId, peer)
                continue
            }
            // Use the PRE-decrement bundle for Router.shouldForward. Router rejects
            // hopsLeft <= 0, so passing the decremented copy (which may be 0 on the
            // last legitimate hop) would silently drop the final forward. The memo is
            // keyed by bundleId anyway, so the hopsLeft value on the argument is only
            // used for the hop/ttl guard — and the pre-decrement value is what we
            // actually validated above.
            if (!router.shouldForward(bundle, peer, nowMs)) continue
            // Relay forwards are not correlated with a local outbound message row —
            // use sendId=0 so PayloadSent doesn't try to advance a non-existent row.
            transport.send(peer, payloadBytes, sendId = 0L)
            MeshLog.forwarded(bundle.bundleId, peer, nextHops)
        }
    }

    private fun snapshotConnectedPeers(): List<String> =
        synchronized(peersLock) { connectedPeers.toList() }

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
                synchronized(peersLock) { connectedPeers.add(event.peerId) }
                // Opportunistic inventory sync with the new peer — covers store-and-forward
                // for bundles that arrived while this peer was offline.
                syncWithPeer(event.peerId)
                val e = MeshEvent.PeerConnected(event.peerId)
                firePeerEvent(e)
                eventListener?.invoke(e)
            }
            is TransportEvent.PeerFound -> {
                firePeerEvent(MeshEvent.PeerFound(event.peerId, event.displayName))
            }
            is TransportEvent.PeerDisconnected -> {
                synchronized(peersLock) { connectedPeers.remove(event.peerId) }
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
        // Drop expired / hop-exhausted at receive time — don't cache, don't ACK, don't forward.
        if (bundle.ttlUntil < nowMs) {
            MeshLog.droppedTtl(bundle.bundleId, peerId)
            return
        }
        if (bundle.hopsLeft < 0) {
            MeshLog.droppedHops(bundle.bundleId, peerId, bundle.hopsLeft)
            return
        }

        val isFirstArrival: Boolean
        var alreadyDelivered = false
        var deliveredSnapshot: MeshBundle? = null
        synchronized(cacheLock) {
            isFirstArrival = !cache.containsKey(bundle.bundleId)
            if (isFirstArrival) {
                cachePutLocked(bundle)
            }
            // Track which peers have given us this bundle — consulted by forwardBundle
            // to implement the anti-sender invariant (never forward back upstream).
            receivedFrom.getOrPut(bundle.bundleId) { HashSet() }.add(peerId)
            if (bundle.destId == localUserId) {
                alreadyDelivered = cache[bundle.bundleId]?.status == "DELIVERED_FINAL"
                val delivered = bundle.copy(status = "DELIVERED_FINAL")
                cache[delivered.bundleId] = delivered
                deliveredSnapshot = delivered
            }
        }
        MeshLog.received(bundle.bundleId, peerId, bundle.destId, bundle.hopsLeft, isFirstArrival)

        if (deliveredSnapshot != null) {
            // Addressed to us: deliver (once) + ACK (every time, so a resend retires).
            if (!alreadyDelivered) {
                eventListener?.invoke(MeshEvent.BundleDelivered(deliveredSnapshot!!))
                MeshLog.delivered(bundle.bundleId)
            } else {
                MeshLog.droppedDuplicate(bundle.bundleId, peerId)
            }
            val ack = ProtocolFrame(ProtocolType.ACK, localUserId, bundle.bundleId)
            sendFrame(peerId, ack)
            return
        }

        // Not addressed to us → relay path. Forward ONLY on first arrival so a
        // ping-ponging peer can't trigger another fanout. Storm prevention also enforced
        // by Router.shouldForward's (bundleId, peerId) memo.
        if (!isFirstArrival) {
            MeshLog.droppedDuplicate(bundle.bundleId, peerId)
            return
        }
        forwardBundle(bundle, fromPeerId = peerId)
    }

    private fun handleAck(ackedBy: String, bundleId: String) {
        // First-ACK idempotency: fire BundleAcked exactly once per bundleId, on the
        // first ACK that arrives for one of OUR outbound bundles. Duplicate ACKs
        // (e.g. a relay chain feeding us back a second copy, or a peer sending the
        // same ACK twice) hit the already-DELIVERED_FINAL guard and are silently
        // dropped — no event fired, no state change.
        val firstAck: Boolean = synchronized(cacheLock) {
            val existing = cache[bundleId] ?: return@synchronized false
            if (existing.srcId != localUserId) return@synchronized false
            if (existing.status == "DELIVERED_FINAL") return@synchronized false
            cache[bundleId] = existing.copy(status = "DELIVERED_FINAL")
            true
        }
        if (firstAck) {
            eventListener?.invoke(MeshEvent.BundleAcked(bundleId, ackedBy))
            MeshLog.acked(bundleId, ackedBy)
        }
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
                // Drop the parallel receivedFrom entry so we don't leak it into the
                // future — if this bundleId reappears, it will be treated as a fresh
                // first-arrival, which is the correct behavior after cache eviction.
                receivedFrom.remove(key)
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
    /**
     * The transport accepted the bundle for send (endpoint known, bytes queued in
     * Nearby's send buffer) but PayloadSent has not yet fired. Emitted exactly once
     * per successful Transport.send() call — the repository advances the message to
     * SENDING. This is the first authoritative "in flight" signal; [BundleSent]
     * below is the confirmation that bytes actually left the radio.
     */
    data class BundleSending(val bundleId: String) : MeshEvent()
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

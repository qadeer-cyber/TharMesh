package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.LinkedHashMap
import java.util.UUID
import java.util.Timer
import java.util.TimerTask

class MeshEngine(
    private val localUserId: String,
    private val transport: Transport,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val HEARTBEAT_MS = 4000L
        private const val INV_BATCH_SIZE = 200
        private const val GET_BATCH_SIZE = 50
        private const val BUNDLE_BATCH_SIZE = 10
        private const val SEEN_CACHE_MAX = 4000
    }

    private val router = Router()
    private val cache: MutableMap<String, MeshBundle> = linkedMapOf()
    private val seenBundleIds: LinkedHashMap<String, Long> = object : LinkedHashMap<String, Long>(SEEN_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > SEEN_CACHE_MAX
        }
    }
    private val peerKnownIds: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val connectedPeers: MutableSet<String> = mutableSetOf()

    private var heartbeatTimer: Timer? = null
    private var onBundleDelivered: ((MeshBundle) -> Unit)? = null
    private var onRelayAck: ((String) -> Unit)? = null
    private var onFinalAck: ((String) -> Unit)? = null

    init {
        transport.setListener { event: TransportEvent ->
            onTransportEvent(event)
        }
    }

    fun start() {
        transport.start(localUserId)
        startHeartbeat()
    }

    fun stop() {
        stopHeartbeat()
        transport.stop()
    }

    fun setBundleListener(listener: (MeshBundle) -> Unit) {
        onBundleDelivered = listener
    }

    fun setRelayAckListener(listener: (String) -> Unit) {
        onRelayAck = listener
    }

    fun setFinalAckListener(listener: (String) -> Unit) {
        onFinalAck = listener
    }

    fun queueText(destId: String, payloadCiphertext: String, ttlMs: Long, maxHops: Int): MeshBundle {
        val ts = now()
        val bundle = MeshBundle(
            bundleId = UUID.randomUUID().toString(),
            srcId = localUserId,
            destId = destId,
            payloadCiphertext = payloadCiphertext,
            ttlUntil = ts + ttlMs,
            hopCount = 0,
            maxHops = maxHops,
            signature = "TODO_SIG",
            status = "QUEUED",
            attemptCount = 0,
            nextRetryAt = ts
        )
        cache[bundle.bundleId] = bundle
        return bundle
    }

    fun syncWithPeer(peerId: String) {
        sendInv(peerId)
        sendBundlesForPeer(peerId)
    }

    private fun onTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerConnected -> {
                connectedPeers.add(event.peerId)
                onPeerConnected(event.peerId)
            }
            is TransportEvent.PeerDisconnected -> {
                connectedPeers.remove(event.peerId)
                peerKnownIds.remove(event.peerId)
            }
            is TransportEvent.PayloadReceived -> {
                val frame = ProtocolCodec.decode(event.bytes) ?: return
                handleFrame(event.peerId, frame)
            }
            else -> {
                // ignore
            }
        }
    }

    private fun onPeerConnected(peerId: String) {
        val hello = ProtocolFrame(
            ProtocolType.HELLO,
            localUserId,
            ProtocolCodec.encodeHello(HelloPacket(localUserId, "TharMesh", "TODO_PUBKEY_HASH"))
        )
        transport.send(peerId, ProtocolCodec.encode(hello))
        sendInv(peerId)
    }

    private fun handleFrame(peerId: String, frame: ProtocolFrame) {
        when (frame.type) {
            ProtocolType.HELLO -> sendInv(peerId)
            ProtocolType.INV -> handleInv(peerId, frame.payload)
            ProtocolType.HAVE -> handleHave(peerId, frame.payload)
            ProtocolType.GET -> handleGet(peerId, frame.payload)
            ProtocolType.BUNDLE -> handleBundle(peerId, frame.payload)
            ProtocolType.ACK_RELAY -> handleAckRelay(peerId, frame.payload)
            ProtocolType.ACK_FINAL -> handleAckFinal(peerId, frame.payload)
        }
    }

    private fun sendInv(peerId: String) {
        val eligible = cache.values
            .filter { it.ttlUntil > now() && it.status != "DELIVERED" && it.status != "EXPIRED" }
            .map { it.bundleId }
            .take(INV_BATCH_SIZE)
        if (eligible.isEmpty()) return
        val frame = ProtocolFrame(ProtocolType.INV, localUserId, ProtocolCodec.encodeInv(InvPacket(eligible)))
        transport.send(peerId, ProtocolCodec.encode(frame))
    }

    private fun handleInv(peerId: String, payload: String) {
        val ids = ProtocolCodec.decodeIds(payload)
        if (ids.isEmpty()) return

        val known = peerKnownIds.getOrPut(peerId) { mutableSetOf() }
        known.addAll(ids)

        val missing = ids.filter { id -> !cache.containsKey(id) }
        val have = ids.filter { id -> cache.containsKey(id) }

        if (have.isNotEmpty()) {
            val haveFrame = ProtocolFrame(ProtocolType.HAVE, localUserId, ProtocolCodec.encodeHave(HavePacket(have.take(INV_BATCH_SIZE))))
            transport.send(peerId, ProtocolCodec.encode(haveFrame))
        }

        if (missing.isNotEmpty()) {
            val getFrame = ProtocolFrame(ProtocolType.GET, localUserId, ProtocolCodec.encodeGet(GetPacket(missing.take(GET_BATCH_SIZE))))
            transport.send(peerId, ProtocolCodec.encode(getFrame))
        }
    }

    private fun handleHave(peerId: String, payload: String) {
        val ids = ProtocolCodec.decodeIds(payload)
        if (ids.isEmpty()) return
        val known = peerKnownIds.getOrPut(peerId) { mutableSetOf() }
        known.addAll(ids)
    }

    private fun handleGet(peerId: String, payload: String) {
        val requested = ProtocolCodec.decodeIds(payload).take(BUNDLE_BATCH_SIZE)
        if (requested.isEmpty()) return

        val peerKnown = peerKnownIds.getOrPut(peerId) { mutableSetOf() }
        for (id in requested) {
            val bundle = cache[id] ?: continue
            if (peerKnown.contains(bundle.bundleId)) continue
            if (!router.shouldForward(bundle, peerId, now())) continue
            if (bundle.status == "DELIVERED" || bundle.status == "EXPIRED") continue

            peerKnown.add(bundle.bundleId)
            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
            transport.send(peerId, ProtocolCodec.encode(frame))
        }
    }

    private fun handleBundle(fromPeerId: String, payload: String) {
        val bundle = BundleCodec.decode(payload) ?: return
        if (bundle.bundleId.isBlank()) return
        if (bundle.ttlUntil <= now()) return
        if (bundle.hopCount >= bundle.maxHops) return
        if (seenBundleIds.containsKey(bundle.bundleId)) return

        seenBundleIds[bundle.bundleId] = now()

        val existing = cache[bundle.bundleId]
        val stored = if (existing == null) {
            bundle.copy(
                hopCount = bundle.hopCount + 1,
                status = if (bundle.destId == localUserId) "DELIVERED" else "RELAYED"
            )
        } else {
            existing
        }
        cache[stored.bundleId] = stored

        val relayAck = ProtocolFrame(
            ProtocolType.ACK_RELAY,
            localUserId,
            ProtocolCodec.encodeAckRelay(
                AckRelayPacket(stored.bundleId, localUserId, now(), "TODO_RELAY_SIG")
            )
        )
        transport.send(fromPeerId, ProtocolCodec.encode(relayAck))

        if (stored.destId == localUserId) {
            onBundleDelivered?.invoke(stored)
            val finalAck = ProtocolFrame(
                ProtocolType.ACK_FINAL,
                localUserId,
                ProtocolCodec.encodeAckFinal(
                    AckFinalPacket(stored.bundleId, stored.srcId, localUserId, now(), "TODO_FINAL_SIG")
                )
            )
            transport.send(fromPeerId, ProtocolCodec.encode(finalAck))
        }
    }

    private fun handleAckRelay(fromPeerId: String, payload: String) {
        val ack = ProtocolCodec.decodeAckRelay(payload) ?: return
        val existing = cache[ack.bundleId] ?: return
        if (existing.status == "DELIVERED") return

        // Basic authenticity guard: relay ack should come from same peer claiming relayUserId.
        if (ack.relayUserId.isBlank() || ack.relayUserId != fromPeerId) return

        cache[ack.bundleId] = applyMonotonicStatus(existing, "RELAYED")
        onRelayAck?.invoke(ack.bundleId)
    }

    private fun handleAckFinal(fromPeerId: String, payload: String) {
        val ack = ProtocolCodec.decodeAckFinal(payload) ?: return
        val existing = cache[ack.bundleId] ?: return

        // Authenticity + intent checks:
        if (ack.toUserId != localUserId) return
        if (ack.fromUserId.isBlank() || fromPeerId != ack.fromUserId) return
        if (existing.srcId != localUserId) return
        if (existing.destId != ack.fromUserId) return

        cache[ack.bundleId] = applyMonotonicStatus(existing, "DELIVERED")
        onFinalAck?.invoke(ack.bundleId)
    }

    private fun applyMonotonicStatus(bundle: MeshBundle, newStatus: String): MeshBundle {
        val currentRank = statusRank(bundle.status)
        val nextRank = statusRank(newStatus)
        return if (nextRank >= currentRank) bundle.copy(status = newStatus) else bundle
    }

    private fun statusRank(status: String): Int {
        return when (status) {
            "QUEUED" -> 0
            "RELAYED" -> 1
            "DELIVERED" -> 2
            "READ" -> 3
            "EXPIRED" -> 4
            else -> 0
        }
    }

    private fun startHeartbeat() {
        if (heartbeatTimer != null) return
        heartbeatTimer = Timer("mesh-heartbeat", true)
        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                tick()
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private fun tick() {
        if (connectedPeers.isEmpty()) return
        val nowTs = now()

        // TTL purge and non-regressive expiry mark.
        val expiredIds = cache.values
            .filter { it.ttlUntil <= nowTs && it.status != "DELIVERED" && it.status != "EXPIRED" }
            .map { it.bundleId }
        for (id in expiredIds) {
            val b = cache[id] ?: continue
            cache[id] = applyMonotonicStatus(b, "EXPIRED")
        }

        for (peerId in connectedPeers) {
            sendInv(peerId)
            sendBundlesForPeer(peerId)
        }
    }

    private fun sendBundlesForPeer(peerId: String) {
        val peerKnown = peerKnownIds.getOrPut(peerId) { mutableSetOf() }
        val nowTs = now()

        val candidates = cache.values
            .filter { it.status == "QUEUED" || it.status == "RELAYED" }
            .filter { it.nextRetryAt <= nowTs }
            .filter { it.ttlUntil > nowTs }
            .filter { !peerKnown.contains(it.bundleId) }
            .take(BUNDLE_BATCH_SIZE)

        for (bundle in candidates) {
            if (!router.shouldForward(bundle, peerId, nowTs)) continue
            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
            val sent = transport.send(peerId, ProtocolCodec.encode(frame))
            val nextAttempt = bundle.attemptCount + 1
            val updated = bundle.copy(
                attemptCount = nextAttempt,
                nextRetryAt = nowTs + backoffForAttempt(nextAttempt)
            )
            if (sent) {
                peerKnown.add(bundle.bundleId)
            }
            cache[updated.bundleId] = updated
        }
    }

    private fun backoffForAttempt(attemptCount: Int): Long {
        return when {
            attemptCount <= 0 -> 3000L
            attemptCount == 1 -> 10000L
            attemptCount == 2 -> 30000L
            attemptCount == 3 -> 120000L
            else -> 600000L
        }
    }
}

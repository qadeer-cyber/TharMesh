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
        private const val TTL_GRACE_MS = 120000L
        private const val INV_BATCH_SIZE = 200
        private const val GET_BATCH_SIZE = 50
        private const val BUNDLE_BATCH_SIZE = 10
        private const val SEEN_CACHE_MAX = 2000
        private const val METRIC_LOG_EVERY_TICKS = 5
        private const val ACK_MAX_AGE_MS = 15L * 60L * 1000L
        private const val ACK_FORWARD_CACHE_MAX = 6000
    }

    private val router = Router()
    private val cache: MutableMap<String, MeshBundle> = linkedMapOf()

    private val seenBundleIds: LinkedHashMap<String, Long> = object : LinkedHashMap<String, Long>(SEEN_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > SEEN_CACHE_MAX
        }
    }

    private val seenFinalAckIds: LinkedHashMap<String, Long> = object : LinkedHashMap<String, Long>(SEEN_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > SEEN_CACHE_MAX
        }
    }


    private val ackForwardedToPeerKeys: LinkedHashMap<String, Long> = object : LinkedHashMap<String, Long>(ACK_FORWARD_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > ACK_FORWARD_CACHE_MAX
        }
    }

    private val peerKnownIds: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val connectedPeers: MutableSet<String> = mutableSetOf()

    private var heartbeatTimer: Timer? = null
    private var onBundleDelivered: ((MeshBundle) -> Unit)? = null
    private var onRelayAck: ((String) -> Unit)? = null
    private var onFinalAck: ((String) -> Unit)? = null

    // Injectable verifier: lookup stored public key by userId and verify sig externally.
    private var ackSignatureVerifier: ((fromUserId: String, payload: String, sig: String) -> Boolean)? = null

    // Metrics
    private var tickCount: Long = 0
    private var duplicateDropCount: Long = 0
    private var deliveredCount: Long = 0
    private var retryAttemptCount: Long = 0

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

    fun setAckSignatureVerifier(verifier: (fromUserId: String, payload: String, sig: String) -> Boolean) {
        ackSignatureVerifier = verifier
    }

    fun snapshotMetrics(): MeshMetricsSnapshot {
        val active = cache.values.count { it.status != "DELIVERED" && it.status != "EXPIRED" }
        val delivered = cache.values.count { it.status == "DELIVERED" }
        val expired = cache.values.count { it.status == "EXPIRED" }
        val readyToSync = cache.values.count { (it.status == "QUEUED" || it.status == "RELAYED") && !isExpired(it) && it.nextRetryAt <= now() }
        return MeshMetricsSnapshot(
            totalBundles = cache.size,
            activeBundles = active,
            deliveredBundles = delivered,
            expiredBundles = expired,
            readyToSyncBundles = readyToSync,
            retriesObserved = retryAttemptCount,
            connectedPeers = connectedPeers.size,
            lastTickAt = now()
        )
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
            .filter { !isExpired(it) }
            .filter { it.status != "DELIVERED" && it.status != "EXPIRED" }
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
            if (isExpired(bundle)) continue

            peerKnown.add(bundle.bundleId)
            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
            transport.send(peerId, ProtocolCodec.encode(frame))
        }
    }

    private fun handleBundle(fromPeerId: String, payload: String) {
        val bundle = BundleCodec.decode(payload) ?: return
        if (bundle.bundleId.isBlank()) return
        if (isExpired(bundle)) return
        if (bundle.hopCount >= bundle.maxHops) return
        if (seenBundleIds.containsKey(bundle.bundleId)) {
            duplicateDropCount += 1
            return
        }

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
        cache[stored.bundleId] = applyMonotonicStatus(stored, stored.status)

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
            deliveredCount += 1
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
        if (existing.status == "DELIVERED" || isExpired(existing)) return

        if (ack.relayUserId.isBlank() || ack.relayUserId != fromPeerId) return

        cache[ack.bundleId] = applyMonotonicStatus(existing, "RELAYED")
        onRelayAck?.invoke(ack.bundleId)
    }

    private fun handleAckFinal(fromPeerId: String, payload: String) {
        val ack = ProtocolCodec.decodeAckFinal(payload) ?: return
        if ((now() - ack.ts) > ACK_MAX_AGE_MS) return
        val existing = cache[ack.bundleId] ?: return

        val replayKey = ack.bundleId + "@" + ack.toUserId + "@" + ack.fromUserId
        if (seenFinalAckIds.containsKey(replayKey)) {
            return
        }

        // If final ACK is not for me, forward it toward origin path (eventual ack convergence).
        if (ack.toUserId != localUserId) {
            forwardAckFinal(fromPeerId, ack, payload)
            seenFinalAckIds[replayKey] = now()
            return
        }

        // idempotent + anti-regression + expiry protection
        if (existing.status == "DELIVERED" || isExpired(existing)) {
            seenFinalAckIds[replayKey] = now()
            return
        }

        // Authenticity + intent checks
        if (ack.fromUserId.isBlank() || fromPeerId != ack.fromUserId) return
        if (existing.srcId != localUserId) return
        if (existing.destId != ack.fromUserId) return

        // Signature verification hook (if not set, treat as invalid in hardened mode)
        val verifier = ackSignatureVerifier ?: return
        val signPayload = ack.bundleId + "|" + ack.toUserId + "|" + ack.fromUserId + "|" + ack.ts
        if (!verifier.invoke(ack.fromUserId, signPayload, ack.sig)) return

        cache[ack.bundleId] = applyMonotonicStatus(existing, "DELIVERED")
        onFinalAck?.invoke(ack.bundleId)
        seenFinalAckIds[replayKey] = now()
    }

    private fun forwardAckFinal(fromPeerId: String, ack: AckFinalPacket, payload: String) {
        if ((now() - ack.ts) > ACK_MAX_AGE_MS) return
        val frame = ProtocolFrame(ProtocolType.ACK_FINAL, localUserId, payload)
        val replayKeyBase = ack.bundleId + "@" + ack.toUserId + "@" + ack.fromUserId
        for (peer in connectedPeers) {
            if (peer == fromPeerId) continue
            val dedupeKey = replayKeyBase + "->" + peer
            if (ackForwardedToPeerKeys.containsKey(dedupeKey)) continue
            // Prefer peers who might not already know this bundle.
            val known = peerKnownIds.getOrPut(peer) { mutableSetOf() }
            if (known.contains(ack.bundleId)) continue
            val sent = transport.send(peer, ProtocolCodec.encode(frame))
            if (sent) {
                ackForwardedToPeerKeys[dedupeKey] = now()
                known.add(ack.bundleId)
            }
        }
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
        tickCount += 1
        val nowTs = now()

        purgeExpired(nowTs)

        if (connectedPeers.isEmpty()) {
            maybeLogMetrics()
            return
        }

        for (peerId in connectedPeers) {
            sendInv(peerId)
            sendBundlesForPeer(peerId)
        }

        maybeLogMetrics()
    }

    private fun purgeExpired(nowTs: Long) {
        val expiredIds = cache.values
            .filter { (it.ttlUntil + TTL_GRACE_MS) <= nowTs && it.status != "DELIVERED" && it.status != "EXPIRED" }
            .map { it.bundleId }
        for (id in expiredIds) {
            val b = cache[id] ?: continue
            cache[id] = applyMonotonicStatus(b, "EXPIRED")
        }
    }

    private fun sendBundlesForPeer(peerId: String) {
        val peerKnown = peerKnownIds.getOrPut(peerId) { mutableSetOf() }
        val nowTs = now()

        val candidates = cache.values
            .filter { it.status == "QUEUED" || it.status == "RELAYED" }
            .filter { it.nextRetryAt <= nowTs }
            .filter { !isExpired(it) }
            .filter { !peerKnown.contains(it.bundleId) }
            .take(BUNDLE_BATCH_SIZE)

        for (bundle in candidates) {
            if (!router.shouldForward(bundle, peerId, nowTs)) continue

            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(bundle))
            val sent = transport.send(peerId, ProtocolCodec.encode(frame))
            retryAttemptCount += 1

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

    private fun isExpired(bundle: MeshBundle): Boolean {
        return (bundle.ttlUntil + TTL_GRACE_MS) <= now()
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

    private fun maybeLogMetrics() {
        if (tickCount % METRIC_LOG_EVERY_TICKS != 0L) return
        println(
            "MeshMetrics tick=$tickCount bundles=${cache.size} delivered=$deliveredCount duplicateDrops=$duplicateDropCount retries=$retryAttemptCount peers=${connectedPeers.size}"
        )
    }
}


data class MeshMetricsSnapshot(
    val totalBundles: Int,
    val activeBundles: Int,
    val deliveredBundles: Int,
    val expiredBundles: Int,
    val readyToSyncBundles: Int,
    val retriesObserved: Long,
    val connectedPeers: Int,
    val lastTickAt: Long
)

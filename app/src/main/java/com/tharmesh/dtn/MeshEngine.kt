package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.LinkedHashMap
import java.util.UUID

class MeshEngine(
    private val localUserId: String,
    private val transport: Transport,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
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
    }

    fun stop() {
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

    fun queueText(destId: String, payloadCiphertext: String, ttlMs: Long, hops: Int): MeshBundle {
        val bundle = MeshBundle(
            bundleId = UUID.randomUUID().toString(),
            srcId = localUserId,
            destId = destId,
            payloadCiphertext = payloadCiphertext,
            ttlUntil = now() + ttlMs,
            hopsLeft = hops,
            signature = "TODO_SIG",
            status = "QUEUED"
        )
        cache[bundle.bundleId] = bundle
        return bundle
    }

    fun syncWithPeer(peerId: String) {
        sendInv(peerId)
    }

    private fun onTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerConnected -> {
                onPeerConnected(event.peerId)
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
            ProtocolType.ACK_RELAY -> handleAckRelay(frame.payload)
            ProtocolType.ACK_FINAL -> handleAckFinal(frame.payload)
        }
    }

    private fun sendInv(peerId: String) {
        val eligible = cache.values
            .filter { it.ttlUntil > now() }
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
            if (!router.shouldForward(bundle, peerId, now())) continue
            if (peerKnown.contains(bundle.bundleId)) continue
            if (bundle.ttlUntil <= now()) continue

            val next = bundle.copy(
                hopsLeft = bundle.hopsLeft - 1,
                status = if (bundle.srcId == localUserId) "RELAYED" else bundle.status
            )
            peerKnown.add(next.bundleId)

            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(next))
            val sent = transport.send(peerId, ProtocolCodec.encode(frame))
            if (sent && next.srcId == localUserId) {
                onRelayAck?.invoke(next.bundleId)
            }
        }
    }

    private fun handleBundle(fromPeerId: String, payload: String) {
        val bundle = BundleCodec.decode(payload) ?: return
        if (bundle.bundleId.isBlank()) return
        if (bundle.ttlUntil <= now()) return
        if (bundle.hopsLeft <= 0) return

        if (seenBundleIds.containsKey(bundle.bundleId)) {
            return
        }
        seenBundleIds[bundle.bundleId] = now()

        if (!cache.containsKey(bundle.bundleId)) {
            cache[bundle.bundleId] = bundle
        }

        val relayAck = ProtocolFrame(
            ProtocolType.ACK_RELAY,
            localUserId,
            ProtocolCodec.encodeAckRelay(
                AckRelayPacket(
                    bundleId = bundle.bundleId,
                    relayUserId = localUserId,
                    ts = now(),
                    sig = "TODO_RELAY_SIG"
                )
            )
        )
        transport.send(fromPeerId, ProtocolCodec.encode(relayAck))

        if (bundle.destId == localUserId) {
            val delivered = bundle.copy(status = "DELIVERED")
            onBundleDelivered?.invoke(delivered)
            val finalAck = ProtocolFrame(
                ProtocolType.ACK_FINAL,
                localUserId,
                ProtocolCodec.encodeAckFinal(
                    AckFinalPacket(
                        bundleId = bundle.bundleId,
                        toUserId = localUserId,
                        ts = now(),
                        sig = "TODO_FINAL_SIG"
                    )
                )
            )
            transport.send(fromPeerId, ProtocolCodec.encode(finalAck))
        }
    }

    private fun handleAckRelay(payload: String) {
        val ack = ProtocolCodec.decodeAckRelay(payload) ?: return
        val existing = cache[ack.bundleId] ?: return
        cache[ack.bundleId] = existing.copy(status = "RELAYED")
        onRelayAck?.invoke(ack.bundleId)
    }

    private fun handleAckFinal(payload: String) {
        val ack = ProtocolCodec.decodeAckFinal(payload) ?: return
        val existing = cache[ack.bundleId] ?: return
        cache[ack.bundleId] = existing.copy(status = "DELIVERED")
        onFinalAck?.invoke(ack.bundleId)
    }
}

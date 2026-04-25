// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.dtn

import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    private val maxCacheSize: Int = DEFAULT_MAX_CACHE_SIZE,
    private val bundleStore: BundleStore? = null,
    /**
     * Our own signing identity. If null, originated bundles go out without a
     * signature (status == "PENDING", srcPubKey == "") — useful for tests that
     * pair with [allowLegacyUnsigned]=true. Production wiring always supplies one.
     */
    private val identity: CryptoIdentity? = null,
    /**
     * Trust store for peer public keys (see [PeerTrustStore]). If null, signature
     * verification still runs but the TOFU rule is skipped — every signed bundle
     * is accepted as long as its signature verifies against its own srcPubKey.
     */
    private val peerTrustStore: PeerTrustStore? = null,
    /**
     * When true, bundles without a srcPubKey / signature are still accepted (the
     * status they had pre-Stage-4.6). Useful for interop with older peers during a
     * rolling upgrade.
     *
     * Default is derived: `identity == null` → true (an engine that cannot sign
     * cannot reasonably demand signatures from peers either — this is the posture
     * for unit tests that bypass identity entirely); `identity != null` → false
     * (production: reject anything that isn't signed). Pass an explicit boolean to
     * override either way.
     */
    allowLegacyUnsigned: Boolean? = null,
    /**
     * Stage 5.2 — optional per-peer send-rate pacer. When supplied, every
     * outbound `transport.send()` call in [broadcastBundle] / [forwardBundle] /
     * [handleGet] checks for an available slot first; deferred sends call
     * [onSendPaced] and skip the peer for this iteration (the retry loop will
     * re-issue origination sends; relay forwards are dropped — the originator
     * retries).
     */
    private val pacer: PerPeerSendPacer? = null,
    /** Diagnostic hook: a send was deferred by [pacer]. */
    private val onSendPaced: (peerId: String) -> Unit = { _ -> },
    /** Diagnostic hook: [transport.send] returned false (transport rejected the bytes). */
    private val onSendRejected: (peerId: String, bundleId: String) -> Unit = { _, _ -> },
    /** Diagnostic hook: a bundle was dropped because its TTL had elapsed. */
    private val onTtlExpiredDrop: (bundleId: String) -> Unit = { _ -> }
) {
    private val allowLegacyUnsigned: Boolean = allowLegacyUnsigned ?: (identity == null)

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

    // Stage 5.1 — additive passive listeners. Used by DiagnosticsCollector (and
    // other non-authoritative observers) to mirror every MeshEvent without
    // displacing the repository's single [eventListener] or duplicating peer
    // events the data source already gets via [peerListeners]. A passive
    // listener receives EXACTLY the same event stream that reaches
    // [eventListener] (bundle events + PeerConnected) PLUS the peer-found /
    // peer-disconnected events the data source gets — i.e. one unified stream
    // with no duplicates. Copy-on-write so the fire path is lock-free.
    @Volatile private var passiveListeners: List<(MeshEvent) -> Unit> = emptyList()
    private val passiveListenersLock = Any()

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

    /**
     * Per-instance retired flag. PR #14 follow-up (PR #15 review):
     * `TharMeshApp.ensureMeshStarted()` launches a deferred coroutine that
     * checks `isStartCurrent(capturedEngine)` BEFORE calling [start], but the
     * check and the call are not atomic — `stopMesh()` can interleave on the
     * main thread between them. Without this flag, the coroutine would
     * resurrect a transport that `stopMesh` had just torn down, leaving the
     * old engine advertising the old identity with no reference to clean it
     * up later (the post-flight gate intentionally avoids calling
     * `capturedEngine.stop()` because it would tear down the singleton
     * `Nearby.getConnectionsClient(applicationContext)` shared by every
     * transport in the process).
     *
     * The check-then-act pair must be atomic with respect to [stop], or
     * else slow Room I/O during [start] (`bundleStore.deleteExpired` +
     * `loadActive`) opens a multi-millisecond window where `stop()` can
     * flip the flag and tear down the transport between the [closed] read
     * and the [Transport.start] call. To close that window, the read
     * paired with [Transport.start] in [start] and the write paired with
     * [Transport.stop] in [stop] both run inside [lifecycleLock].
     *
     * A new `MeshEngine` for a different identity has its own [closed]
     * flag and its own [lifecycleLock], so this never blocks legitimate
     * restarts on a sign-out → sign-in cycle.
     */
    private val closed = AtomicBoolean(false)

    /**
     * Serialises the [closed]-check + [Transport.start] pair against the
     * [closed]-set + [Transport.stop] pair. Held only across the actual
     * transport-touching calls; the slow cache-rehydrate work in [start]
     * runs OUTSIDE the lock so a concurrent [stop] doesn't block the main
     * thread on Room I/O. See [closed] kdoc for the race this closes.
     */
    private val lifecycleLock = Any()

    init {
        transport.setListener { event: TransportEvent ->
            onTransportEvent(event)
        }
    }

    fun start() {
        // Cheap early-out: if we were already retired before any I/O began,
        // skip the rehydrate work entirely. This is purely an optimisation;
        // the authoritative gate is inside [lifecycleLock] below.
        if (closed.get()) {
            MeshLog.startAfterStopIgnored(localUserId)
            return
        }
        // Rehydrate the in-memory cache from the persistent store BEFORE the transport
        // comes up. Two invariants matter:
        //  1. An incoming INV sync frame after start() must reflect bundles we already
        //     knew about pre-crash, otherwise we would re-request BUNDLE frames we
        //     already have on disk and cause a resend storm.
        //  2. The store-and-forward retry loop (MessageRepository) calls retryBundle
        //     for every pending outbound row in Room; retryBundle looks the bundle up
        //     in the cache, so the cache must be populated before the first tick.
        //
        // This block runs OUTSIDE [lifecycleLock] so a concurrent [stop] from
        // the main thread isn't blocked on Room I/O. If [stop] wins the race
        // and flips [closed] while we're rehydrating, the lock-protected
        // re-check below will see it and bail before [Transport.start] runs;
        // the cache work we just did is harmless (this engine instance is
        // about to be discarded).
        val store = bundleStore
        if (store != null) {
            store.deleteExpired(now())
            val restored = store.loadActive(now())
            synchronized(cacheLock) {
                for (b in restored) {
                    // Use the raw map write, not cachePutLocked — we do NOT want to
                    // re-persist during restore (no-op but wasteful), and we do NOT
                    // want LRU eviction to kick in while we're loading the working set.
                    cache[b.bundleId] = b
                }
            }
            MeshLog.restored(restored.size)
        }
        // Authoritative gate: the lock-protected re-check + [Transport.start]
        // pair is atomic w.r.t. [stop]'s [closed]-set + [Transport.stop] pair.
        // After this block, either we started the transport AND [stop] (if any)
        // will subsequently tear it down cleanly, or we observed [closed]=true
        // and never touched the transport. No interleaving can produce a
        // zombie transport on the singleton ConnectionsClient.
        synchronized(lifecycleLock) {
            if (closed.get()) {
                MeshLog.startAfterStopIgnored(localUserId)
                return
            }
            transport.start(localUserId)
        }
    }

    fun stop() {
        // Atomic w.r.t. [start]: setting [closed] before [Transport.stop]
        // ensures a concurrent [start] that loses the race will see
        // [closed]=true on its lock-protected re-check and skip
        // [Transport.start] entirely. Holding the lock around the
        // [Transport.stop] call also ensures we cannot tear down a transport
        // that [start] is mid-bringing-up.
        synchronized(lifecycleLock) {
            closed.set(true)
            transport.stop()
        }
        // The transport's stop() tears down endpoints but does not fire PeerDisconnected
        // events — manually clear the peer-connection set so a subsequent start() does
        // not try to send to stale peers that would immediately fail and flip messages
        // to FAILED. receivedFrom is tied to the same session's transport identity, so
        // clear it too. These can run outside [lifecycleLock] — they don't
        // touch the transport.
        synchronized(peersLock) { connectedPeers.clear() }
        synchronized(cacheLock) { receivedFrom.clear() }
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
     * Subscribe to EVERY [MeshEvent] the engine emits — bundle events and peer
     * events — exactly once per emission. Stage 5.1 introduces this for
     * diagnostics / field-test observers. Additive, thread-safe, lock-free fire
     * path. Never wire authoritative pipeline logic through this API; use
     * [setEventListener] for single-owner concerns (the message repository).
     */
    fun addEventListener(listener: (MeshEvent) -> Unit) {
        synchronized(passiveListenersLock) {
            passiveListeners = passiveListeners + listener
        }
    }

    fun removeEventListener(listener: (MeshEvent) -> Unit) {
        synchronized(passiveListenersLock) {
            passiveListeners = passiveListeners - listener
        }
    }

    private fun firePassive(event: MeshEvent) {
        val snapshot = passiveListeners
        for (l in snapshot) {
            try {
                l(event)
            } catch (_: Throwable) {
                // Observers must not be able to break the mesh engine.
            }
        }
    }

    /**
     * Unified emit for events that go through the repository's single
     * [eventListener]. Passive observers (diagnostics) see the same event.
     */
    private fun emit(event: MeshEvent) {
        eventListener?.invoke(event)
        firePassive(event)
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
        bundleIdHint: String? = null,
        /**
         * Stage 5.3 — origination-only priority bit. Set by the SOS path so the
         * resulting bundle bypasses [PerPeerSendPacer] in [broadcastBundle] /
         * [forwardBundle] / [handleGet]. Off-wire (see [MeshBundle.priority]).
         */
        priority: Boolean = false
    ): MeshBundle {
        val bundleId = bundleIdHint ?: UUID.randomUUID().toString()
        val ttlUntil = now() + ttlMs
        // Sign the end-to-end-invariant fields (NOT hopsLeft / status — those mutate
        // per relay hop and would invalidate the signature downstream). Fall back to
        // empty signature + empty pubKey when no identity is wired (tests only).
        val id = identity
        val signature: String
        val srcPubKey: String
        if (id != null) {
            val canonical = CryptoIdentity.canonicalBundleBytes(
                bundleId = bundleId,
                srcId = localUserId,
                destId = destId,
                payloadCiphertext = payloadCiphertext,
                ttlUntil = ttlUntil
            )
            signature = id.sign(canonical)
            srcPubKey = id.publicKeyBase64
            MeshLog.bundleSigned(bundleId)
        } else {
            signature = ""
            srcPubKey = ""
        }
        val bundle = MeshBundle(
            bundleId = bundleId,
            srcId = localUserId,
            destId = destId,
            payloadCiphertext = payloadCiphertext,
            ttlUntil = ttlUntil,
            hopsLeft = hops.coerceAtLeast(0),
            signature = signature,
            status = "PENDING",
            srcPubKey = srcPubKey,
            priority = priority
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
     *
     * Returns `true` when a re-broadcast was actually attempted, `false` when the call was
     * a no-op (cache miss / already DELIVERED_FINAL / TTL expired). The repository uses the
     * return value to decide whether to advance the [RetryPolicy] state and increment the
     * `retryAttempts` diagnostic. On TTL expiry, callers should invoke
     * [RetryPolicy.onTtlExpired] to free per-bundle state — otherwise the policy map would
     * grow unboundedly because Room rows whose TTL elapsed are still returned by
     * `pendingOutbound` until status flips to FAILED/DELIVERED.
     */
    fun retryBundle(bundleId: String): Boolean {
        val bundle = synchronized(cacheLock) { cache[bundleId] } ?: return false
        if (bundle.srcId != localUserId) return false
        if (bundle.status == "DELIVERED_FINAL") return false
        if (bundle.ttlUntil < now()) {
            // Stage 5.2: surface TTL-expired drops on the retry path so the
            // diagnostics collector counts them. broadcastBundle has its own
            // TTL guard for the origination path; retryBundle's early return
            // means broadcastBundle never sees this expired bundle.
            onTtlExpiredDrop(bundleId)
            return false
        }
        broadcastBundle(bundle)
        return true
    }

    /**
     * True iff at least one peer is currently connected at the transport layer.
     * Used by the store-and-forward retry loop to suppress retries during an
     * outage — without this the loop would call [retryBundle] →
     * [broadcastBundle], which logs `noConnectedPeers` and returns silently,
     * yet the caller still recorded a retry attempt and consumed the
     * [RetryPolicy] backoff curve. Checking at the loop level keeps per-bundle
     * retry state untouched during the outage so the nextRetryAt window
     * doesn't march up to the maxDelay ceiling before the peer returns.
     */
    fun hasConnectedPeers(): Boolean = synchronized(peersLock) { connectedPeers.isNotEmpty() }

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
            onTtlExpiredDrop(bundle.bundleId)
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
            //
            // Stage 5.2: per-peer send pacing. If the pacer rejects, defer this peer
            // (the next retry tick re-issues the broadcast and the gap will have elapsed).
            // Stage 5.3: priority bundles (SOS) skip the pacer — they fan out at the
            // full rate even when many normal bundles are paced. The pacer's per-peer
            // 40 ms gap exists to avoid overwhelming Nearby's send buffer; SOS payloads
            // are tiny and the spec demands aggressive delivery.
            if (!bundle.priority && pacer != null && !pacer.acquireSlot(peer, nowMs)) {
                onSendPaced(peer)
                continue
            }
            val sendId = sendIdSeq.getAndIncrement()
            // Track the correlation BEFORE calling send — a synchronous failure path inside
            // send (e.g. LoopbackTransport "Peer not connected") will fire Error(sendId)
            // synchronously and pop from the map.
            synchronized(pendingLock) { pendingBundleSends[sendId] = bundle.bundleId }
            val accepted = transport.send(peer, payloadBytes, sendId)
            if (!accepted) {
                synchronized(pendingLock) { pendingBundleSends.remove(sendId) }
                onSendRejected(peer, bundle.bundleId)
                continue
            }
            MeshLog.sending(bundle.bundleId, peer)
            anyAccepted = true
        }
        if (anyAccepted) {
            // Repository advances QUEUED → SENDING. Actual "bytes on wire" confirmation
            // still comes later per-peer via TransportEvent.PayloadSent, at which point
            // BundleSent (→ status=SENT) is emitted in onTransportEvent.
            emit(MeshEvent.BundleSending(bundle.bundleId))
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
            onTtlExpiredDrop(bundle.bundleId)
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
        // Persist the post-decrement state so a crash mid-forward does not repeat the
        // same forward with the pre-decrement hopsLeft on restart. We intentionally do
        // NOT update the in-memory cache to `forwarded` — the original bundle is kept
        // at its original hopsLeft so handleBundle's first-arrival guard still works if
        // the same bundleId arrives from another peer. Cache = working set; DB = source
        // of truth for the authoritative relay history.
        bundleStore?.upsert(forwarded)
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
            // Stage 5.2: per-peer pacing applies to relays too — if the pacer
            // rejects, drop this forward (the originator's retry will eventually
            // re-broadcast and the relay path will fire on the next forward).
            if (pacer != null && !pacer.acquireSlot(peer, nowMs)) {
                onSendPaced(peer)
                continue
            }
            // Relay forwards are not correlated with a local outbound message row —
            // use sendId=0 so PayloadSent doesn't try to advance a non-existent row.
            val accepted = transport.send(peer, payloadBytes, sendId = 0L)
            if (!accepted) {
                onSendRejected(peer, bundle.bundleId)
                continue
            }
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
                    emit(MeshEvent.BundleSent(bundleId))
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
                emit(e)
            }
            is TransportEvent.PeerFound -> {
                val e = MeshEvent.PeerFound(event.peerId, event.displayName)
                firePeerEvent(e)
                firePassive(e)
            }
            is TransportEvent.PeerDisconnected -> {
                synchronized(peersLock) { connectedPeers.remove(event.peerId) }
                val e = MeshEvent.PeerDisconnected(event.peerId)
                firePeerEvent(e)
                firePassive(e)
            }
            is TransportEvent.Error -> {
                val bundleId = synchronized(pendingLock) { pendingBundleSends.remove(event.sendId) }
                if (bundleId != null) {
                    emit(MeshEvent.BundleFailed(bundleId, event.reason))
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
            // Stage 5.3: per-peer pacing applies to GET responses too — without
            // this, a peer that requested many bundles via INV/GET would receive
            // them all in a tight burst and could overwhelm the Nearby send
            // buffer (the very scenario PerPeerSendPacer was added to prevent).
            // Skip-and-let-originator-retry is fine: dropped GET responses are
            // covered by the next origination retry tick.
            //
            // Priority bundles (SOS) bypass the pacer here for the same reason
            // they bypass it in [broadcastBundle]: the contract on [queueText]
            // promises full-rate fanout for SOS, including via the INV/GET
            // serving path when a peer connects late and pulls cached bundles.
            if (!bundle.priority && pacer != null && !pacer.acquireSlot(peerId, nowMs)) {
                onSendPaced(peerId)
                continue
            }
            val nextHops = (bundle.hopsLeft - 1).coerceAtLeast(0)
            val next = bundle.copy(hopsLeft = nextHops, status = "FORWARDED")
            val frame = ProtocolFrame(ProtocolType.BUNDLE, localUserId, BundleCodec.encode(next))
            sendFrame(peerId, frame)
        }
    }

    /**
     * Run the Stage 4.6 signature + TOFU gate for an incoming [bundle]. Returns true
     * if the bundle should continue into the handleBundle pipeline (cache, deliver,
     * relay); false if the bundle must be silently dropped.
     *
     * Call sites: currently only [handleBundle]. Kept as a helper so tests can
     * assert verification behaviour without driving the full transport path.
     *
     * Bundles originated by [localUserId] always pass (a device trusts its own
     * origination; relays forward without re-signing).
     */
    private fun verifyIncoming(bundle: MeshBundle): Boolean {
        // Our own bundle echoed back via relay — trust it. handleBundle's anti-sender
        // and dedup guards still prevent infinite relay storms.
        if (bundle.srcId == localUserId) return true

        val unsigned = bundle.signature.isEmpty() || bundle.srcPubKey.isEmpty()
        if (unsigned) {
            if (allowLegacyUnsigned) return true
            MeshLog.signatureFailed(bundle.bundleId, bundle.srcId, "unsigned_rejected")
            return false
        }

        val trust = peerTrustStore
        if (trust != null) {
            when (val verdict = trust.verdict(bundle.srcId, bundle.srcPubKey)) {
                is PeerTrustStore.Verdict.FirstSeen ->
                    MeshLog.peerKeyFirstSeen(bundle.srcId, CryptoIdentity.fingerprintOf(bundle.srcPubKey))
                is PeerTrustStore.Verdict.Match -> {
                    // no-op, proceed to cryptographic verify below
                }
                is PeerTrustStore.Verdict.Mismatch -> {
                    MeshLog.peerKeyMismatch(
                        userId = bundle.srcId,
                        storedFp = verdict.storedFingerprint,
                        presentedFp = verdict.presentedFingerprint
                    )
                    return false
                }
            }
        }

        val canonical = CryptoIdentity.canonicalBundleBytes(
            bundleId = bundle.bundleId,
            srcId = bundle.srcId,
            destId = bundle.destId,
            payloadCiphertext = bundle.payloadCiphertext,
            ttlUntil = bundle.ttlUntil
        )
        val ok = CryptoIdentity.verify(bundle.srcPubKey, canonical, bundle.signature)
        if (!ok) {
            MeshLog.signatureFailed(bundle.bundleId, bundle.srcId, "ecdsa_verify_failed")
            return false
        }
        MeshLog.signatureVerified(
            bundleId = bundle.bundleId,
            srcId = bundle.srcId,
            fp = CryptoIdentity.fingerprintOf(bundle.srcPubKey)
        )
        return true
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

        // Stage 4.6 signature + TOFU gate. Order matters:
        //   1) Unsigned bundle → reject unless allowLegacyUnsigned (tests / rolling upgrade).
        //   2) TOFU check against peer_identity store — if the srcId previously signed with
        //      a different key, reject here BEFORE cryptographic verify. This protects
        //      against an attacker that minted a valid signature with their own keypair
        //      but reused someone else's srcId.
        //   3) ECDSA verify against the canonical signing blob. Any failure → reject.
        // A rejected bundle is NOT cached, NOT ACK'd, NOT forwarded — as if it never
        // arrived. That is the only safe posture: caching or ACK'ing a bad bundle would
        // leak DoS amplification to the attacker.
        if (!verifyIncoming(bundle)) return

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
        // Persist outside the cache lock. If this was a first arrival, upsert the full
        // bundle; if we're also the destination, the subsequent persistStatus flips the
        // on-disk status to DELIVERED_FINAL so a restart does not re-fire BundleDelivered.
        if (isFirstArrival) bundleStore?.upsert(bundle)
        if (deliveredSnapshot != null && !alreadyDelivered) {
            persistStatus(bundle.bundleId, "DELIVERED_FINAL")
        }
        MeshLog.received(bundle.bundleId, peerId, bundle.destId, bundle.hopsLeft, isFirstArrival)

        if (deliveredSnapshot != null) {
            // Addressed to us: deliver (once) + ACK (every time, so a resend retires).
            if (!alreadyDelivered) {
                emit(MeshEvent.BundleDelivered(deliveredSnapshot!!))
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
            // Mirror the DELIVERED_FINAL status to disk so a restart does not treat the
            // bundle as still-pending and try to re-send.
            persistStatus(bundleId, "DELIVERED_FINAL")
            emit(MeshEvent.BundleAcked(bundleId, ackedBy))
            MeshLog.acked(bundleId, ackedBy)
        }
    }

    private fun handleRead(readBy: String, bundleId: String) {
        val fireEvent: Boolean = synchronized(cacheLock) {
            val existing = cache[bundleId] ?: return@synchronized false
            existing.srcId == localUserId
        }
        if (fireEvent) emit(MeshEvent.BundleRead(bundleId, readBy))
    }

    /**
     * Insert-or-update a bundle in the cache with LRU eviction when we exceed [maxCacheSize].
     * Also persists to the [BundleStore] so the bundle survives process death. Public-ish
     * only for tests; production paths use this via queueText/handleBundle/forwardBundle.
     */
    internal fun cachePut(bundle: MeshBundle) {
        synchronized(cacheLock) { cachePutLocked(bundle) }
        // Persist AFTER the cache mutation (outside the lock) so a slow Room write
        // does not block concurrent cache readers. Idempotent: REPLACE on bundleId.
        bundleStore?.upsert(bundle)
    }

    /** Just update the on-disk status without re-writing the full payload. */
    private fun persistStatus(bundleId: String, status: String) {
        bundleStore?.updateStatus(bundleId, status)
    }

    /**
     * Periodic sweep of the persistent store. Called by the repository's store-and-forward
     * tick so long-lived expired rows do not accumulate unboundedly on disk.
     */
    fun sweepExpiredPersistent() {
        bundleStore?.deleteExpired(now())
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

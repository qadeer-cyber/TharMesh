// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.data

import com.tharmesh.db.AppDatabase
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.dtn.MeshBundle
import com.tharmesh.dtn.MeshEngine
import com.tharmesh.dtn.MeshEvent
import com.tharmesh.dtn.PeerChurnDebouncer
import com.tharmesh.dtn.RetryConfig
import com.tharmesh.dtn.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Orchestrates message persistence (Room) ⇄ mesh transport ([MeshEngine]).
 *
 * Responsibilities:
 *  - `send(toUserId, body, replyToId)` writes a QUEUED [MessageEntity], queues a [MeshBundle],
 *    and updates the status as transport + mesh events arrive.
 *  - Exposes [observeConversation] / [observeConversations] / [observeContacts] flows for the
 *    UI.
 *  - `markChatRead(peerId)` flips incoming messages from this peer to READ locally and emits
 *    READ receipts over the mesh so the sender's UI shows the blue ticks.
 *  - Handles incoming bundles from [MeshEngine], unseals the envelope when
 *    present, persists the message, and bumps the conversation row.
 *
 * Encryption: when a [com.tharmesh.crypto.PeerKeyRing] is wired, the send
 * path wraps the body in a [com.tharmesh.crypto.SealedEnvelope] (AES-256 /
 * GCM over an ECDH-derived key); receive reverses the wrap. Peers whose
 * public keys we have not pinned yet fall back to plaintext bodies so the
 * TOFU bootstrap path remains open. The SOS framing runs OUTSIDE the
 * envelope so encrypted SOS bundles still trigger the disaster alert.
 */
class MessageRepository(
    private val db: AppDatabase,
    private val mesh: MeshEngine,
    private val myUserId: () -> String,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Stage 5.2 — retry timing knobs. Tests inject a tight schedule. */
    private val retryConfig: RetryConfig = RetryConfig.DEFAULT,
    /**
     * Stage 5.2 — per-bundle backoff state. Defaults to a fresh policy seeded
     * from [retryConfig]. Tests pass a custom one (e.g. with a deterministic
     * jitter source) to lock in expected next-retry timings.
     */
    private val retryPolicy: RetryPolicy = RetryPolicy(retryConfig),
    /** Diagnostic hook: the retry loop re-broadcast a bundle. */
    private val onRetryAttempt: (bundleId: String) -> Unit = { _ -> },
    /** Diagnostic hook: a bundle row was found in SENDING state and re-issued. */
    private val onStuckSendingRecovered: (bundleId: String) -> Unit = { _ -> },
    /** Diagnostic hook: a peer reconnect was suppressed by the churn debouncer. */
    private val onPeerChurnSuppressed: (peerId: String) -> Unit = { _ -> },
    /**
     * Diagnostic hook: a retry-tick iteration was skipped because the mesh had
     * no connected peers. Fires once per eligible pending bundle per tick, so
     * field testers can correlate retry droughts with outages. The per-bundle
     * [RetryPolicy] state is intentionally untouched — when the peer returns
     * the normal shouldAttempt/recordAttempt flow resumes at the same backoff
     * window instead of marching to the ceiling during the outage.
     */
    private val onRetrySuppressedNoPeers: (bundleId: String) -> Unit = { _ -> },
    /**
     * Stage 6.3 — fired from [handleIncomingBundle] when an inbound bundle
     * carries the SOS payload prefix. The default no-op keeps the receive
     * path side-effect-free in tests; production wiring sets this to
     * [com.tharmesh.disaster.DisasterModeController.onSosReceived] so the
     * device vibrates + rings on inbound priority traffic when disaster
     * mode is locally enabled.
     */
    private val onSosReceived: () -> Unit = {},
    /**
     * Stage 7 PR E — fired after a contact has been upserted. Production
     * wiring increments [GrowthMetrics.recordContactAdded]; tests use the
     * default no-op. The hook fires for every successful upsert,
     * including re-adds (the existing call sites tolerate re-adding an
     * existing contact, so this is intentionally non-deduplicated — a
     * contact going from removed → re-added is a fresh acquisition for
     * growth purposes).
     */
    private val onContactAdded: (userId: String) -> Unit = { _ -> },
    /**
     * Stage 7 PR E — fired exactly once per `(myUserId, peerUserId)`
     * pair, the first time the user sends a message to that peer. Used
     * to drive [GrowthMetrics.recordChatStarted] + the post-first-chat
     * viral prompt. The repository checks the message DAO under the
     * IO dispatcher to ensure idempotency across process restarts.
     */
    private val onFirstChatStarted: (peerUserId: String) -> Unit = { _ -> },
    /**
     * Stage 7.4 — fired on every entry to [send]. Drives the
     * `direct_send_attempt` diagnostic counter (one per send call,
     * regardless of online/offline state, regardless of priority).
     */
    private val onDirectSendAttempt: () -> Unit = {},
    /**
     * Stage 7.4 — fired when [send] runs while [MeshEngine.hasConnectedPeers]
     * is false (i.e. the bundle is going straight into the offline
     * queue and will only flush after at least one peer connects).
     * Drives the `queued_offline` counter. Receives the bundleId so
     * the diagnostic ring buffer can correlate with the matching
     * `auto_delivered_on_reconnect` event later.
     */
    private val onQueuedOffline: (bundleId: String) -> Unit = { _ -> },
    /**
     * Stage 7.4 — fired exactly once per bundleId when a bundle that
     * was [onQueuedOffline] later transitions to SENT (i.e. after a
     * peer reconnect / mesh tick). Drives the
     * `auto_delivered_on_reconnect` counter and proves the
     * store-and-forward queue is actually flushing. Bundles that were
     * sent online or never queued offline never trigger this.
     */
    private val onAutoDeliveredOnReconnect: (bundleId: String) -> Unit = { _ -> },
    /**
     * Stage 6.3 — gating predicate consulted on every [send] / [broadcastSos]
     * call. When true, every outgoing bundle is force-marked priority (SOS
     * retry curve + pacer bypass) and its body is wrapped with the SOS
     * payload prefix so receivers can recognise it. Defaults to `false` so
     * tests don't accidentally trigger disaster behaviour.
     */
    private val isDisasterModeEnabled: () -> Boolean = { false },
    /**
     * Optional per-peer AES key ring. When provided, [send] wraps the
     * outgoing body in a [com.tharmesh.crypto.SealedEnvelope] for any peer
     * whose public key we have pinned, and [handleIncomingBundle] unwraps
     * envelopes it recognises. When null (production bring-up before the
     * key ring is wired, or tests that don't care about encryption), both
     * paths fall back to plaintext bodies. The SOS prefix and all wire
     * signatures are applied OUTSIDE the envelope, so this change is
     * invisible to relays and TOFU key-pinning.
     */
    private val peerKeyRing: com.tharmesh.crypto.PeerKeyRing? = null,
    /**
     * Optional persistence mirror for [retryPolicy] + [priorityBundleIds].
     * When present, retry-curve state and the SOS priority bit survive a
     * forced process kill — on the next construction, [hydrateFromDisk]
     * seeds [retryPolicy] and [priorityBundleIds] from the persisted rows
     * so SOS bundles stay on their aggressive curve instead of falling
     * back to the default 5s→60s curve after a crash. Null in tests that
     * don't care about persistence; the plain in-memory behaviour is
     * preserved in that case.
     */
    private val retryStatePersistence: RetryStatePersistence? = null
) {

    /**
     * Stage 5.3 — set of bundleIds that should be retried using the aggressive
     * [RetryConfig.SOS] curve instead of the [retryConfig] default. Populated
     * by [broadcastSos] and cleared in [onMeshEvent] when the bundle reaches
     * a truly terminal state — DELIVERED or READ — and in the retry tick when
     * the bundle's TTL expires (see [runRetryTick]). FAILED is intentionally
     * NOT a clear point: FAILED rows are included in the store-and-forward
     * [pendingOutbound] sweep AND are eligible for manual [retryFailedMessage]
     * retries, so the bundle should keep the SOS curve until it is actually
     * acked or the TTL expires. Synchronised on itself.
     */
    private val priorityBundleIds: MutableSet<String> = HashSet()

    private fun isPriority(bundleId: String): Boolean =
        synchronized(priorityBundleIds) { bundleId in priorityBundleIds }

    private fun clearPriority(bundleId: String) {
        synchronized(priorityBundleIds) { priorityBundleIds.remove(bundleId) }
    }

    /**
     * Stage 7.4 — bundleIds that entered the queue while the mesh had
     * zero connected peers. When [MeshEvent.BundleSent] later fires for
     * one of these IDs we know the store-and-forward queue actually
     * flushed after a reconnect, and we bump the
     * `auto_delivered_on_reconnect` counter exactly once per bundle.
     * Bundles sent while peers were already connected never enter this
     * tracker, so they cannot inflate the counter. The tracker is a
     * package-private helper class (see end of file) so unit tests can
     * exercise the consume-once semantics without spinning up the full
     * repository.
     */
    private val offlineQueuedTracker = OfflineQueuedBundleTracker()

    /**
     * Stage 5.2 — coalesce rapid `PeerConnected` triggers for the same peer into
     * a single trailing flush. Driven by the retry-loop tick (see
     * [startStoreAndForwardLoop]).
     */
    private val churnDebouncer = PeerChurnDebouncer(
        windowMs = retryConfig.churnDebounceMs,
        action = { peerId ->
            // Off-thread to avoid holding the retry-loop's IO dispatcher slot for the
            // duration of a flush; flushPendingForLocalUser hits the DB.
            scope.launch(Dispatchers.IO) {
                flushPendingForLocalUser()
                mesh.syncWithPeer(peerId)
                // Stage 5.2: a NEW peer reconnect resets every tracked bundle's
                // attempt counter back to 0. The topology may have improved
                // (this peer might be the missing relay) so backoff on stale
                // state is no longer informative.
                retryPolicy.onPeerConnectedBypass(now())
                // The bypass rewrote every tracked bundle's state; re-mirror
                // each row so a subsequent cold start doesn't restart from
                // the pre-bypass curve.
                persistAllTrackedRetryState()
            }
        }
    )

    init {
        mesh.setEventListener { event -> onMeshEvent(event) }
        // Hydrate persisted retry state + SOS priority set before any tick
        // fires — the retry loop is started separately by
        // [startStoreAndForwardLoop], which posts to [scope]; by then the
        // blocking load below has already seeded the policy.
        retryStatePersistence?.let { hydrateRetryStateFromDisk(it) }
    }

    private fun hydrateRetryStateFromDisk(p: RetryStatePersistence) {
        val rows = try {
            p.loadAll()
        } catch (ignored: Throwable) {
            return
        }
        if (rows.isEmpty()) return
        val seed = HashMap<String, RetryPolicy.BundleState>(rows.size)
        synchronized(priorityBundleIds) {
            for (row in rows) {
                seed[row.bundleId] = RetryPolicy.BundleState(
                    attemptCount = row.attemptCount,
                    nextRetryAt = row.nextRetryAt
                )
                if (row.priority) priorityBundleIds.add(row.bundleId)
            }
        }
        retryPolicy.hydrate(seed)
    }

    private fun persistRetryState(bundleId: String) {
        val p = retryStatePersistence ?: return
        val state = retryPolicy.currentState(bundleId) ?: return
        val priority = isPriority(bundleId)
        scope.launch(Dispatchers.IO) {
            try {
                p.save(bundleId, state, priority)
            } catch (ignored: Throwable) {
                // Persistence is best-effort. A lost write means, at worst, the
                // bundle restarts its curve from base delay on next cold start
                // — i.e. the pre-PR behaviour. Swallow rather than crash.
            }
        }
    }

    private fun persistRetryStateRemove(bundleId: String) {
        val p = retryStatePersistence ?: return
        scope.launch(Dispatchers.IO) {
            try {
                p.remove(bundleId)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun persistAllTrackedRetryState() {
        val p = retryStatePersistence ?: return
        val snapshot = retryPolicy.snapshot()
        if (snapshot.isEmpty()) return
        val priorityCopy = synchronized(priorityBundleIds) { priorityBundleIds.toSet() }
        scope.launch(Dispatchers.IO) {
            for ((bid, st) in snapshot) {
                try {
                    p.save(bid, st, bid in priorityCopy)
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    /**
     * Metrics the Status screen reads. Incremented as mesh events arrive; never decremented.
     * Survives process death only if we ever write them to SharedPreferences — for now they
     * are best-effort, session-scoped numbers.
     */
    @Volatile var messagesRelayed: Long = 0L
        private set
    @Volatile var messagesDelivered: Long = 0L
        private set
    @Volatile var messagesSent: Long = 0L
        private set

    private var retryJob: Job? = null

    fun observeConversation(peerId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeConversation(peerId)

    fun observeConversations(): Flow<List<ConversationEntity>> =
        db.conversationDao().observeAll()

    fun observeContacts(): Flow<List<ContactEntity>> =
        db.contactDao().observeAll()

    /** Ensure a conversation row exists for the given peer, seeded with [title]. */
    suspend fun ensureConversation(peerUserId: String, title: String = peerUserId) {
        runIo {
            val convDao = db.conversationDao()
            val existing = convDao.getByUserId(peerUserId)
            if (existing == null) {
                convDao.upsert(
                    ConversationEntity(
                        userId = peerUserId,
                        title = title,
                        lastMessage = "",
                        lastTimestamp = now(),
                        lastMessageStatus = "",
                        unreadCount = 0
                    )
                )
            }
        }
    }

    suspend fun addContact(userId: String, displayName: String = userId): ContactEntity {
        return runIo {
            val existing = db.contactDao().getByUserId(userId)
            val entity = existing?.copy(
                displayName = displayName.ifBlank { userId },
                lastSeen = now()
            ) ?: ContactEntity(
                userId = userId,
                displayName = displayName.ifBlank { userId },
                publicKey = "",
                addedAt = now(),
                lastSeen = now()
            )
            db.contactDao().upsert(entity)
            ensureConversationBlocking(userId, entity.displayName)
            entity
        }.also { onContactAdded(userId) }
    }

    /**
     * PR B — remove a contact by userId. Deletes the [ContactEntity] only;
     * conversation history (`conversations` + `messages`) and the TOFU pin
     * in `peer_identity` are intentionally preserved so the user can re-add
     * the contact later and resume the same thread + verified shield. Safe
     * to call for unknown userIds (returns 0).
     */
    suspend fun removeContact(userId: String): Int {
        return runIo { db.contactDao().deleteByUserId(userId) }
    }

    private fun ensureConversationBlocking(peerUserId: String, title: String) {
        val convDao = db.conversationDao()
        if (convDao.getByUserId(peerUserId) == null) {
            convDao.upsert(
                ConversationEntity(
                    userId = peerUserId,
                    title = title,
                    lastMessage = "",
                    lastTimestamp = now(),
                    lastMessageStatus = "",
                    unreadCount = 0
                )
            )
        }
    }

    data class SendResult(val messageId: Long, val bundleId: String)

    /**
     * Enqueue a message to [toUserId]. Returns immediately after the row is written and the
     * bundle is handed off to the mesh engine; status transitions arrive asynchronously.
     *
     * Stage 5.3 — [priority] marks this bundle as SOS / urgent: the engine
     * skips the per-peer pacer when fanning out, and the retry loop applies
     * [RetryConfig.SOS] (1s→2s→4s→8s) instead of the standard backoff. The
     * priority bit is local-only (not on the wire — see [MeshBundle.priority]).
     */
    suspend fun send(
        toUserId: String,
        body: String,
        replyToId: Long? = null,
        priority: Boolean = false
    ): SendResult {
        val bundleId = UUID.randomUUID().toString()
        val ts = now()
        val me = myUserId()
        // Stage 7.4 — every send call counts as one direct_send_attempt
        // (the user tapped a contact and pressed send; the device picker
        // is no longer in the loop for known contacts). When the mesh
        // has no connected peers at this moment the bundle goes into
        // the offline queue and we record it so we can detect the
        // matching BundleSent on reconnect.
        onDirectSendAttempt()
        if (!mesh.hasConnectedPeers()) {
            offlineQueuedTracker.markQueuedOffline(bundleId)
            onQueuedOffline(bundleId)
        }
        val replyPreview = replyToId?.let { runIo { db.messageDao().getById(it) }?.body }
        // Stage 6.3 — when disaster mode is on globally, force every outgoing
        // bundle onto the SOS retry curve + pacer-bypass + alert path.
        val effectivePriority = priority || isDisasterModeEnabled()
        // Local DB stores the user's raw body so the chat history is clean;
        // the wire-side payload carries the SOS prefix only when the bundle
        // is actually priority. Idempotent — broadcastSos can also pre-mark.
        val sosFramed = if (effectivePriority) {
            com.tharmesh.disaster.SosPayload.encode(body)
        } else {
            body
        }
        // Apply end-to-end encryption OUTSIDE the SOS frame so the on-wire
        // ciphertext hides both the message body and the SOS marker from
        // eavesdroppers; relays don't look at body content anyway (they
        // forward bytes keyed by destId). Falls back to plaintext when we
        // have no pinned public key for the recipient — this is the
        // TOFU/bootstrap path for peers whose keys we haven't learnt yet.
        val wireBody = peerKeyRing
            ?.keyFor(toUserId)
            ?.let { com.tharmesh.crypto.SealedEnvelope.seal(sosFramed, it) }
            ?: sosFramed
        val entity = MessageEntity(
            fromUserId = me,
            toUserId = toUserId,
            peerUserId = toUserId,
            body = body,
            status = MessageStatus.QUEUED,
            timestamp = ts,
            bundleId = bundleId,
            replyToId = replyToId,
            replyToPreview = replyPreview
        )
        val (messageId, isFirstChat) = runIo {
            // Stage 7 PR E — detect "first message ever sent to this
            // peer" before insert so the GrowthMetrics hook fires
            // exactly once per peer across the lifetime of the install.
            //
            // The count-then-insert pair runs inside a single Room
            // transaction so two concurrent send() calls for the same
            // peer (rapid double-tap, or a user send racing
            // [broadcastSos]) cannot both observe count == 0 and both
            // fire the hook. SQLite serialises transactions on the
            // write connection, so the second send always sees count
            // >= 1 and skips the hook.
            var first = false
            var id = 0L
            db.runInTransaction {
                first = db.messageDao().countOutgoingTo(me, toUserId) == 0
                id = db.messageDao().insert(entity)
                bumpConversation(toUserId, body, ts, MessageStatus.QUEUED, incrementUnread = false)
            }
            id to first
        }
        if (isFirstChat) onFirstChatStarted(toUserId)

        if (effectivePriority) {
            synchronized(priorityBundleIds) { priorityBundleIds.add(bundleId) }
        }
        // See comment on markOriginated below for the ordering: we persist
        // AFTER markOriginated so the row includes the seeded BundleState.
        mesh.queueText(
            destId = toUserId,
            payloadCiphertext = wireBody,
            ttlMs = DEFAULT_TTL_MS,
            hops = DEFAULT_HOPS,
            bundleIdHint = bundleId,
            priority = effectivePriority
        )
        // Seed an ack-grace window so the store-and-forward retry loop, which
        // ticks every RetryConfig.tickIntervalMs (1 s by default), does not
        // re-broadcast within ~1 s of the first send — the peer needs at
        // least one base-delay window to ACK before we hammer the wire again.
        // Diagnostic from real two-device test showed BundleSent at t and a
        // RetryAttempt at t+580 ms because no policy state had been seeded.
        val cfg = if (effectivePriority) RetryConfig.SOS else null
        retryPolicy.markOriginated(bundleId, ts, cfg)
        persistRetryState(bundleId)
        return SendResult(messageId, bundleId)
    }

    /**
     * User opened the chat for [peerUserId]. Reset unread count and mark all incoming messages
     * from that peer as READ (locally + over the mesh).
     */
    suspend fun markChatRead(peerUserId: String) {
        val ts = now()
        runIo {
            val pendingBundleIds = db.messageDao().pendingReadBundleIds(peerUserId)
            db.messageDao().markIncomingRead(peerUserId, ts)
            db.conversationDao().resetUnread(peerUserId)
            pendingBundleIds
        }.forEach { bundleId ->
            if (bundleId.isNotEmpty()) {
                mesh.sendRead(bundleId, peerUserId)
            }
        }
    }

    /** Feed an incoming bundle from transport back into Room. Called by [onMeshEvent]. */
    private fun handleIncomingBundle(bundle: MeshBundle) {
        scope.launch(Dispatchers.IO) {
            val me = myUserId()
            if (bundle.destId != me) return@launch
            val existing = db.messageDao().getByBundleId(bundle.bundleId)
            if (existing != null) return@launch

            // Unseal end-to-end envelope when present. Unknown prefix or a
            // decrypt failure falls back to the raw body — preserves the
            // plaintext path for peers that haven't been key-pinned yet and
            // keeps the receive loop working through a key-rotation window.
            // SOS detection runs OUTSIDE the envelope, so encrypted SOS
            // bundles still trigger the alert once decrypted.
            val raw = bundle.payloadCiphertext
            val unsealed = if (com.tharmesh.crypto.SealedEnvelope.looksSealed(raw)) {
                peerKeyRing
                    ?.keyFor(bundle.srcId)
                    ?.let { com.tharmesh.crypto.SealedEnvelope.unseal(raw, it) }
                    ?: raw
            } else {
                raw
            }
            val decoded = com.tharmesh.disaster.SosPayload.decode(unsealed)
            val plaintext = decoded.body
            if (decoded.isSos) {
                // Fire the alert hook regardless of whether the local user is
                // the bundle's intended destination — a relayed SOS that we
                // happen to overhear should still alert if disaster mode is
                // on. The hook itself is gated by the controller's enabled
                // flag, so off-mode peers stay silent.
                onSosReceived()
            }
            val ts = now()
            val entity = MessageEntity(
                fromUserId = bundle.srcId,
                toUserId = me,
                peerUserId = bundle.srcId,
                body = plaintext,
                status = MessageStatus.DELIVERED,
                timestamp = ts,
                bundleId = bundle.bundleId,
                deliveredAt = ts
            )
            db.messageDao().insert(entity)
            db.contactDao().getByUserId(bundle.srcId) ?: db.contactDao().upsert(
                ContactEntity(
                    userId = bundle.srcId,
                    displayName = bundle.srcId,
                    publicKey = "",
                    addedAt = ts,
                    lastSeen = ts
                )
            )
            db.conversationDao().getByUserId(bundle.srcId) ?: db.conversationDao().upsert(
                ConversationEntity(
                    userId = bundle.srcId,
                    title = bundle.srcId,
                    lastMessage = plaintext,
                    lastTimestamp = ts,
                    lastMessageStatus = "",
                    unreadCount = 0
                )
            )
            bumpConversation(bundle.srcId, plaintext, ts, "", incrementUnread = true)
        }
    }

    private fun onMeshEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.BundleSending -> scope.launch(Dispatchers.IO) {
                // Transport accepted the bundle; bytes are queued inside Nearby but
                // PayloadSent has not yet fired. Authoritative "in flight" state.
                advanceByBundleId(event.bundleId, MessageStatus.SENDING)
            }
            is MeshEvent.BundleSent -> scope.launch(Dispatchers.IO) {
                messagesSent++
                advanceByBundleId(event.bundleId, MessageStatus.SENT)
                // Stage 7.4 — if this bundle was queued while no peers
                // were connected, we just proved the store-and-forward
                // queue flushed after a reconnect. Remove-and-test so
                // the counter fires exactly once even if BundleSent
                // arrives twice for the same bundleId (defensive: the
                // mesh layer dedupes by bundleId, but the set guards
                // against any double-fire from re-tries hitting the
                // same id).
                if (offlineQueuedTracker.consumeOnSent(event.bundleId)) {
                    onAutoDeliveredOnReconnect(event.bundleId)
                }
            }
            is MeshEvent.BundleAcked -> scope.launch(Dispatchers.IO) {
                messagesDelivered++
                advanceByBundleId(event.bundleId, MessageStatus.DELIVERED)
                // Stage 5.2: drop retry state — the bundle is delivered.
                retryPolicy.onDelivered(event.bundleId)
                // Stage 5.3: drop SOS priority tracking too.
                synchronized(priorityBundleIds) { priorityBundleIds.remove(event.bundleId) }
                // Stage 7.4: drop offline-queue tracking. ACK is a
                // terminal state, no further BundleSent will fire,
                // so the entry would otherwise leak.
                offlineQueuedTracker.consumeOnSent(event.bundleId)
                persistRetryStateRemove(event.bundleId)
            }
            is MeshEvent.BundleRead -> scope.launch(Dispatchers.IO) {
                advanceByBundleId(event.bundleId, MessageStatus.READ)
                retryPolicy.onDelivered(event.bundleId)
                synchronized(priorityBundleIds) { priorityBundleIds.remove(event.bundleId) }
                offlineQueuedTracker.consumeOnSent(event.bundleId)
                persistRetryStateRemove(event.bundleId)
            }
            is MeshEvent.BundleDelivered -> {
                messagesRelayed++
                handleIncomingBundle(event.bundle)
            }
            is MeshEvent.BundleFailed -> scope.launch(Dispatchers.IO) {
                // Stage 7.4: drop the offline-queue tracker entry. A
                // FAILED bundle will not produce a BundleSent, so the
                // tracker would otherwise retain the id indefinitely.
                // Note that the row may still be retried later (manual
                // retry / restart) — that path goes through send() which
                // mints a new bundleId, so re-tracking will happen with
                // the new id and not collide with this stale entry.
                offlineQueuedTracker.consumeOnSent(event.bundleId)
                // Only flip rows that are still in-flight (QUEUED or SENDING). A late
                // Error arriving after the message already advanced to SENT/DELIVERED/READ
                // must not regress it — the retry loop promoted it forward correctly.
                val updated = db.messageDao().markFailedIfStillInFlight(event.bundleId)
                if (updated > 0) {
                    val msg = db.messageDao().getByBundleId(event.bundleId) ?: return@launch
                    // Re-check: a concurrent BundleSent/BundleAcked coroutine may have
                    // already advanced the message past FAILED (fanout to multiple peers
                    // where one fails and another succeeds). Only reflect FAILED in the
                    // conversation row if the message is still actually FAILED.
                    if (msg.status == MessageStatus.FAILED) {
                        db.conversationDao().setLastMessage(
                            msg.peerUserId, msg.body, msg.timestamp, MessageStatus.FAILED
                        )
                    }
                }
            }
            is MeshEvent.PeerConnected -> {
                // Stage 5.2: coalesce rapid reconnects for the same peer into a
                // single trailing flush. The debouncer's action runs on the IO
                // dispatcher inside the retry-loop tick — see startStoreAndForwardLoop.
                val peerId = event.peerId
                val before = churnDebouncer.suppressedTotal()
                churnDebouncer.onPeerConnected(peerId, now())
                if (churnDebouncer.suppressedTotal() > before) {
                    onPeerChurnSuppressed(peerId)
                }
            }
            is MeshEvent.PeerFound,
            is MeshEvent.PeerDisconnected -> {
                // Data source handles these; nothing for the repository to do.
            }
        }
    }

    /**
     * Re-broadcast every undelivered outbound message we know about. Called on
     * [MeshEvent.PeerConnected] and from the store-and-forward timer.
     */
    private fun flushPendingForLocalUser() {
        val pending = db.messageDao().pendingOutbound(myUserId())
        for (msg in pending) {
            val id = msg.bundleId ?: continue
            mesh.retryBundle(id)
        }
    }

    /**
     * Broadcast a best-effort SOS text to every currently online peer in the directory.
     * Returns the number of peers the SOS was queued to — the Status screen surfaces this
     * as "SOS sent to N nodes".
     *
     * Stage 5.3 — every emitted bundle is marked priority so it (a) bypasses the
     * per-peer send pacer in the mesh engine, fanning out at full rate, and (b)
     * is retried on the [RetryConfig.SOS] aggressive curve (1s→2s→4s→8s, no
     * jitter) instead of the standard backoff. The priority bit is local-only
     * (off-wire), so a relaying peer that forwards the bundle does NOT inherit
     * priority — a deliberate guard against a malicious peer DDoSing the local
     * pacer with a forged "priority" bit.
     */
    suspend fun broadcastSos(text: String, targets: List<String>): Int {
        if (targets.isEmpty()) return 0
        // The SOS payload prefix is added by [send] when priority=true, so we
        // pass the raw body here. The local message row keeps the clean text
        // and the wire bundle carries the SOS:: marker for receiver detection.
        for (target in targets) {
            send(target, text, priority = true)
        }
        return targets.size
    }

    /**
     * Stage 5.3 — manually re-issue a FAILED message. Tap-to-retry hook from
     * the chat UI: the message row is flipped back to QUEUED (so it leaves the
     * "FAILED" rendering immediately) and the bundle is re-queued through the
     * engine using the SAME bundleId so any previously-cached relays still
     * deduplicate correctly. Returns true when the row was found and was in
     * FAILED state; false if it doesn't exist or has already advanced past
     * FAILED (a concurrent recovery raced us — the user's tap should be a
     * no-op rather than regressing the row).
     *
     * Implementation note — we deliberately do NOT re-insert the message row
     * with a new bundleId; the mesh engine's cache is keyed on the original
     * bundleId, so a fresh id would orphan the cached signature. Instead we
     * advance status QUEUED → SENDING via the same path as the auto-retry
     * loop and let the existing `pendingOutbound` query pick it up on the
     * next tick (or fire immediately on `mesh.retryBundle`).
     */
    suspend fun retryFailedMessage(messageId: Long): Boolean = runIo {
        val msg = db.messageDao().getById(messageId) ?: return@runIo false
        val bid = msg.bundleId ?: return@runIo false
        if (msg.status != MessageStatus.FAILED) return@runIo false
        // Flip back to QUEUED so the chat list immediately drops the "!" glyph
        // and shows "⏳" while we re-issue. The retry loop's pendingOutbound
        // query already includes FAILED rows, but flipping to QUEUED makes the
        // UI feedback instant.
        //
        // Use the rank-protected advanceStatusByBundleId rather than the
        // unconditional updateStatusById: between our `msg.status == FAILED`
        // read above and the write here, a concurrent BundleAcked / BundleRead
        // coroutine may have advanced the row past FAILED to DELIVERED / READ
        // (rare but possible if a relay's ack landed in the gap). Rank-based
        // advance treats QUEUED (rank 0) > FAILED (rank -1) as a legal forward
        // step, but QUEUED < DELIVERED (3) / READ (4) blocks the regression
        // and returns 0. In that case we surface false to the caller so the
        // chat UI's "already advanced" Toast fires instead of silently winning
        // a write race.
        val updated = db.messageDao().advanceStatusByBundleId(bid, MessageStatus.QUEUED, now())
        if (updated == 0) return@runIo false
        db.conversationDao().setLastMessage(
            msg.peerUserId, msg.body, msg.timestamp, MessageStatus.QUEUED
        )
        // Drop any stale retry-policy state for this bundleId so the manual
        // retry doesn't have to wait out a backoff window — the next tick
        // re-issues immediately.
        retryPolicy.onDelivered(bid)
        persistRetryStateRemove(bid)
        // Best-effort: kick the engine immediately so we don't wait for the
        // next tick. retryBundle returns false on cache miss / TTL expiry —
        // either way the result is silently ignored; the retry loop will
        // pick it up if the cache repopulates.
        mesh.retryBundle(bid)
        true
    }

    /**
     * Start a coroutine that ticks every [RetryConfig.tickIntervalMs] and, for
     * each still-undelivered outbound message, asks [retryPolicy] whether the
     * per-bundle backoff window has elapsed. If yes, hands the bundle back to
     * the mesh engine for another broadcast attempt and records the attempt
     * (advancing the per-bundle nextRetryAt). Also drives [churnDebouncer]'s
     * trailing fire and the persistent-bundle TTL sweep.
     *
     * Stage 5.2: replaces the flat 15s sweep with a per-bundle exponential
     * backoff. There is no max-attempt cap — retries stop only on delivery /
     * read or TTL expiry (the engine drops expired bundles from the cache, so
     * `mesh.retryBundle()` becomes a no-op for them).
     */
    fun startStoreAndForwardLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(retryConfig.tickIntervalMs)
                try {
                    val nowMs = now()
                    // Fire any peer-churn settled actions first — these may queue
                    // an immediate flush via flushPendingForLocalUser, which the
                    // per-bundle policy below will then pick up.
                    churnDebouncer.processDue(nowMs)
                    runRetryTick(nowMs, db.messageDao().pendingOutbound(myUserId()))
                    // Opportunistic expired-bundle cleanup — keeps the persistent
                    // bundle table from growing unboundedly when the app stays up
                    // for long periods. Cheap: single DELETE-WHERE on an indexed
                    // column.
                    mesh.sweepExpiredPersistent()
                } catch (_: Throwable) {
                    // Ignore transient I/O failures; next tick will retry.
                }
            }
        }
    }

    /**
     * Single retry-tick body — visible for tests so the policy can be exercised
     * without spinning up the coroutine loop or a real Room DAO. Production
     * passes `db.messageDao().pendingOutbound(myUserId())` for [pending]; tests
     * inject a deterministic list (incl. SENDING-state rows) to exercise the
     * stuck-recovery path.
     */
    internal fun runRetryTick(nowMs: Long, pending: List<MessageEntity>) {
        runRetryTickStandalone(
            nowMs = nowMs,
            pending = pending,
            retryPolicy = retryPolicy,
            onRetryAttempt = onRetryAttempt,
            onStuckSendingRecovered = onStuckSendingRecovered,
            retryBundle = { bid -> mesh.retryBundle(bid) },
            // Stage 5.3 — SOS bundles use the aggressive curve; non-priority
            // bundles fall through to the policy's default config.
            // Stage 6.3 — when disaster mode is on, every pending bundle is
            // promoted to the SOS curve so even pre-toggle queued messages
            // benefit from the aggressive backoff.
            configFor = { bid ->
                if (isPriority(bid) || isDisasterModeEnabled()) RetryConfig.SOS else null
            },
            // Stage 5.3 — drop SOS priority tracking on TTL expiry too, not
            // just on DELIVERED / READ. Without this the priority set leaks
            // one UUID per SOS target whose TTL eventually elapses.
            // Stage 7.4 — also drop the offline-queue tracker entry on
            // TTL expiry. A bundle that expires before any peer connects
            // will never produce BundleSent, so without this the tracker
            // would retain the id for the lifetime of the process.
            onTtlClear = { bid ->
                clearPriority(bid)
                offlineQueuedTracker.consumeOnSent(bid)
            },
            // Skip retry work when no peers are connected — prevents the
            // RetryPolicy backoff curve from being consumed during outages.
            hasConnectedPeers = { mesh.hasConnectedPeers() },
            onRetrySuppressedNoPeers = onRetrySuppressedNoPeers,
            onStatePersisted = { bid -> persistRetryState(bid) },
            onStateForgotten = { bid -> persistRetryStateRemove(bid) }
        )
    }

    fun stopStoreAndForwardLoop() {
        retryJob?.cancel()
        retryJob = null
        retryPolicy.reset()
        churnDebouncer.reset()
    }

    /**
     * Advance the status of the message with [bundleId] to [target] atomically. The SQL
     * UPDATE only fires when [target]'s rank is strictly higher than the current rank
     * (see [com.tharmesh.db.dao.MessageDao.advanceStatusByBundleId]) so two concurrent
     * mesh events cannot race to regress the status. Only refreshes the conversation row
     * when the advance actually took effect.
     */
    private fun advanceByBundleId(bundleId: String, target: String) {
        val msg = db.messageDao().getByBundleId(bundleId) ?: return
        val ts = now()
        val updated = db.messageDao().advanceStatusByBundleId(bundleId, target, ts)
        if (updated > 0) {
            db.conversationDao().setLastMessage(msg.peerUserId, msg.body, msg.timestamp, target)
        }
    }

    private fun bumpConversation(
        peerId: String,
        lastMessage: String,
        ts: Long,
        lastStatus: String,
        incrementUnread: Boolean
    ) {
        val convDao = db.conversationDao()
        val updated = convDao.setLastMessage(peerId, lastMessage, ts, lastStatus)
        if (updated == 0) {
            // setLastMessage returns 0 both when (a) the row doesn't exist yet, and
            // (b) the existing row already has a strictly-newer timestamp. Only insert
            // in case (a); in case (b) an upsert(REPLACE) would clobber the newer row's
            // body + unreadCount.
            val existing = convDao.getByUserId(peerId)
            if (existing == null) {
                convDao.upsert(
                    ConversationEntity(
                        userId = peerId,
                        title = peerId,
                        lastMessage = lastMessage,
                        lastTimestamp = ts,
                        lastMessageStatus = lastStatus,
                        unreadCount = if (incrementUnread) 1 else 0
                    )
                )
            } else if (incrementUnread) {
                convDao.incrementUnread(peerId)
            }
        } else if (incrementUnread) {
            convDao.incrementUnread(peerId)
        }
    }

    private suspend fun <T> runIo(block: () -> T): T =
        withContext(Dispatchers.IO) { block() }

    companion object {
        const val DEFAULT_TTL_MS: Long = 24L * 60L * 60L * 1000L
        const val DEFAULT_HOPS: Int = 8
        /**
         * Pre-Stage-5.2 retry-loop tick interval. Kept as a public constant for any
         * external caller that still references it; the live tick rate is now driven
         * by [RetryConfig.tickIntervalMs] (default 1000 ms).
         */
        @Deprecated("Stage 5.2 — retry tick is now driven by RetryConfig.tickIntervalMs.")
        const val STORE_AND_FORWARD_INTERVAL_MS: Long = 15_000L
    }
}

/**
 * Stage 5.2 — retry-tick body extracted for direct unit testing. Lives at the
 * file level (not on [MessageRepository]) so tests don't need to instantiate a
 * RoomDatabase to exercise the per-bundle backoff + stuck-SENDING recovery
 * logic. Walks every pending outbound row, consults the retry policy, surfaces
 * stuck rows, and re-broadcasts via the supplied [retryBundle] lambda.
 *
 * Production callers go through [MessageRepository.runRetryTick] which wires
 * [retryBundle] to [com.tharmesh.dtn.MeshEngine.retryBundle].
 *
 * Contract for [retryBundle]:
 *  - Returns `true` if a re-broadcast was actually attempted.
 *  - Returns `false` for any no-op path (cache miss, already DELIVERED_FINAL,
 *    or **TTL expired**). On `false` we deliberately:
 *      - skip [onRetryAttempt] (so `diagnostics.retryAttempts` is not inflated
 *        by repeated no-ops on a stuck row),
 *      - skip [RetryPolicy.recordAttempt] (so the policy backoff curve isn't
 *        consumed by no-ops), and
 *      - call [RetryPolicy.onTtlExpired] to free the per-bundle policy state.
 *        Without this, the policy's internal map would grow unboundedly because
 *        Room rows whose TTL elapsed are still returned by `pendingOutbound`
 *        until status flips to FAILED/DELIVERED.
 *
 * The TTL-drop diagnostic itself is fired inside [com.tharmesh.dtn.MeshEngine.retryBundle]
 * via `onTtlExpiredDrop`. To avoid inflating that counter on every tick for the
 * same expired bundle, we also short-circuit subsequent ticks here: once
 * [RetryPolicy.onTtlExpired] frees state, the **first** tick to encounter the
 * row will drop it; the bundle is then absent from the policy map, and on the
 * next tick we re-create state with `recordAttempt` only if `retryBundle`
 * returns true (i.e. the cache was repopulated and TTL is no longer expired —
 * this would only happen if the row's TTL was bumped, which doesn't occur in
 * normal flow). In practice this means a TTL-expired row hits the diagnostic
 * exactly once, then is silent until Room status updates.
 */
internal fun runRetryTickStandalone(
    nowMs: Long,
    pending: List<MessageEntity>,
    retryPolicy: RetryPolicy,
    onRetryAttempt: (String) -> Unit,
    onStuckSendingRecovered: (String) -> Unit,
    retryBundle: (String) -> Boolean,
    /**
     * Stage 5.3 — per-bundle config override. Returns the [RetryConfig] to use
     * when computing the next-attempt delay for [bundleId], or `null` to fall
     * through to the policy's default config. The SOS path supplies
     * [RetryConfig.SOS] so priority bundles retry aggressively (1s→2s→4s→8s)
     * while normal bundles continue on the standard 5s→10s→…→60s curve.
     * Default: always return null (single-curve behaviour, identical to Stage 5.2).
     */
    configFor: (String) -> RetryConfig? = { null },
    /**
     * Stage 5.3 — invoked when [retryBundle] returns false (the no-op path,
     * most commonly TTL expiry). Lets the caller drop any per-bundle bookkeeping
     * keyed off the bundleId — e.g. SOS priority tracking — that would
     * otherwise leak alongside the now-released [retryPolicy] state. Default:
     * no-op so non-priority callers see Stage 5.2 behaviour unchanged.
     */
    onTtlClear: (String) -> Unit = { },
    /**
     * True iff the transport currently has at least one connected peer. When
     * false, a tick skips the per-bundle retry work entirely — the policy
     * state is intentionally untouched so the backoff curve does not advance
     * during an outage. Default: always true (preserves pre-existing
     * behaviour for callers that don't care about this gate).
     */
    hasConnectedPeers: () -> Boolean = { true },
    /**
     * Invoked once per eligible pending bundle per tick when a retry was
     * skipped because [hasConnectedPeers] returned false. Lets diagnostics
     * correlate retry droughts with outages without inflating retryAttempts
     * (no send actually happened).
     */
    onRetrySuppressedNoPeers: (String) -> Unit = { },
    /**
     * Fires AFTER [retryPolicy] has recorded a successful attempt, so the
     * caller can mirror the new [RetryPolicy.BundleState] to a persistence
     * layer (see [RetryStatePersistence]). Default: no-op.
     */
    onStatePersisted: (String) -> Unit = { },
    /**
     * Fires AFTER [retryPolicy] has freed state via `onTtlExpired` inside
     * this tick, so the caller can remove the corresponding persisted row.
     * Default: no-op.
     */
    onStateForgotten: (String) -> Unit = { }
) {
    // Check peers once per tick rather than per bundle — the set changes at
    // transport-event granularity, not within a single sweep. Avoids taking
    // [MeshEngine.peersLock] N times per tick when nothing has changed.
    val peersAvailable = hasConnectedPeers()
    for (msg in pending) {
        val bid = msg.bundleId ?: continue
        if (!retryPolicy.shouldAttempt(bid, nowMs)) continue
        if (!peersAvailable) {
            // No peers — skip the retry but do NOT touch per-bundle policy state.
            // The next tick after PeerConnected will pick up exactly where we
            // left off (nextRetryAt unchanged), and retryAllPendingForLocalUser
            // fires an immediate opportunistic broadcast on reconnect anyway.
            onRetrySuppressedNoPeers(bid)
            continue
        }
        // Snapshot the stuck-SENDING state BEFORE attempting retry — the hook
        // fires only on a successful re-broadcast (no point reporting recovery
        // for a TTL-expired row that won't actually go out).
        val wasStuckSending = msg.status == MessageStatus.SENDING
        val attempted = retryBundle(bid)
        if (!attempted) {
            // No-op path — most commonly TTL expiry. Free per-bundle state to
            // avoid unbounded growth in the policy map; do NOT increment retry
            // counters, since no retry actually happened.
            retryPolicy.onTtlExpired(bid)
            onTtlClear(bid)
            onStateForgotten(bid)
            continue
        }
        if (wasStuckSending) {
            onStuckSendingRecovered(bid)
        }
        onRetryAttempt(bid)
        retryPolicy.recordAttempt(bid, nowMs, configFor(bid))
        onStatePersisted(bid)
    }
}

/**
 * Stage 7.4 — pure thread-safe helper that records bundleIds queued
 * while no peers were connected and reports exactly once when each is
 * later observed delivered.
 *
 *  - [markQueuedOffline] is idempotent: calling it twice for the same
 *    bundleId still produces only one [consumeOnSent] hit.
 *  - [consumeOnSent] returns `true` the first time for a previously
 *    [markQueuedOffline]-tracked id, and `false` thereafter (and for ids
 *    that were never tracked). This guarantees the
 *    `auto_delivered_on_reconnect` counter cannot double-fire even if
 *    `BundleSent` arrives twice for the same id (e.g. a transport-side
 *    retry replay).
 *
 * The implementation is a `HashSet` guarded by an intrinsic lock — the
 * tracker is read on the IO-dispatcher coroutine that handles
 * `MeshEvent.BundleSent` and written from the `send()` suspend
 * function, so it must be safe under contention.
 */
internal class OfflineQueuedBundleTracker {
    private val ids = HashSet<String>()
    private val lock = Any()

    fun markQueuedOffline(bundleId: String) {
        synchronized(lock) { ids.add(bundleId) }
    }

    /** @return true the FIRST time after a [markQueuedOffline] for this id. */
    fun consumeOnSent(bundleId: String): Boolean =
        synchronized(lock) { ids.remove(bundleId) }

    /** Test-only — current size of the in-memory queue. */
    fun trackedCount(): Int = synchronized(lock) { ids.size }
}

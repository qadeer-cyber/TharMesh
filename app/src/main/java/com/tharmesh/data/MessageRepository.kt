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
 *  - Handles incoming bundles from [MeshEngine], decrypts (TODO), persists the message, and
 *    bumps the conversation row (last message + unread++).
 *
 * Encryption is a TODO: bodies ride the wire as plaintext in [MeshBundle.payloadCiphertext].
 * Wire AES-GCM via [com.tharmesh.crypto.CryptoBox] once per-contact key agreement is in place.
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
    private val onPeerChurnSuppressed: (peerId: String) -> Unit = { _ -> }
) {

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
            }
        }
    )

    init {
        mesh.setEventListener { event -> onMeshEvent(event) }
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
        }
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
     */
    suspend fun send(toUserId: String, body: String, replyToId: Long? = null): SendResult {
        val bundleId = UUID.randomUUID().toString()
        val ts = now()
        val me = myUserId()
        val replyPreview = replyToId?.let { runIo { db.messageDao().getById(it) }?.body }
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
        val messageId = runIo {
            val id = db.messageDao().insert(entity)
            bumpConversation(toUserId, body, ts, MessageStatus.QUEUED, incrementUnread = false)
            id
        }

        // Plaintext body on the wire for now; TODO: CryptoBox.encrypt(body, perContactKey, iv).
        mesh.queueText(
            destId = toUserId,
            payloadCiphertext = body,
            ttlMs = DEFAULT_TTL_MS,
            hops = DEFAULT_HOPS,
            bundleIdHint = bundleId
        )
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

            // TODO: CryptoBox.decrypt(bundle.payloadCiphertext, perContactKey, iv)
            val plaintext = bundle.payloadCiphertext
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
            }
            is MeshEvent.BundleAcked -> scope.launch(Dispatchers.IO) {
                messagesDelivered++
                advanceByBundleId(event.bundleId, MessageStatus.DELIVERED)
                // Stage 5.2: drop retry state — the bundle is delivered.
                retryPolicy.onDelivered(event.bundleId)
            }
            is MeshEvent.BundleRead -> scope.launch(Dispatchers.IO) {
                advanceByBundleId(event.bundleId, MessageStatus.READ)
                retryPolicy.onDelivered(event.bundleId)
            }
            is MeshEvent.BundleDelivered -> {
                messagesRelayed++
                handleIncomingBundle(event.bundle)
            }
            is MeshEvent.BundleFailed -> scope.launch(Dispatchers.IO) {
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
     */
    suspend fun broadcastSos(text: String, targets: List<String>): Int {
        if (targets.isEmpty()) return 0
        for (target in targets) {
            send(target, text)
        }
        return targets.size
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
            retryBundle = { bid -> mesh.retryBundle(bid) }
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
    retryBundle: (String) -> Boolean
) {
    for (msg in pending) {
        val bid = msg.bundleId ?: continue
        if (!retryPolicy.shouldAttempt(bid, nowMs)) continue
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
            continue
        }
        if (wasStuckSending) {
            onStuckSendingRecovered(bid)
        }
        onRetryAttempt(bid)
        retryPolicy.recordAttempt(bid, nowMs)
    }
}

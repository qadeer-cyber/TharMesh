package com.tharmesh.data

import com.tharmesh.db.AppDatabase
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.dtn.MeshBundle
import com.tharmesh.dtn.MeshEngine
import com.tharmesh.dtn.MeshEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    init {
        mesh.setEventListener { event -> onMeshEvent(event) }
    }

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
            is MeshEvent.BundleSent -> scope.launch(Dispatchers.IO) {
                advanceByBundleId(event.bundleId, MessageStatus.SENT)
            }
            is MeshEvent.BundleAcked -> scope.launch(Dispatchers.IO) {
                advanceByBundleId(event.bundleId, MessageStatus.DELIVERED)
            }
            is MeshEvent.BundleRead -> scope.launch(Dispatchers.IO) {
                advanceByBundleId(event.bundleId, MessageStatus.READ)
            }
            is MeshEvent.BundleDelivered -> handleIncomingBundle(event.bundle)
        }
    }

    private fun advanceByBundleId(bundleId: String, target: String) {
        val msg = db.messageDao().getByBundleId(bundleId) ?: return
        val next = MessageStatus.advance(msg.status, target)
        if (next == msg.status) return
        val ts = now()
        db.messageDao().updateStatusByBundleId(bundleId, next, ts)
        db.conversationDao().setLastMessage(msg.peerUserId, msg.body, msg.timestamp, next)
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
    }

    private suspend fun <T> runIo(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    companion object {
        const val DEFAULT_TTL_MS: Long = 24L * 60L * 60L * 1000L
        const val DEFAULT_HOPS: Int = 8
    }
}

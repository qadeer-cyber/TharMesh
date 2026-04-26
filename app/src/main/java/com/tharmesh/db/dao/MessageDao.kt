package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(messageEntity: MessageEntity): Long

    @Query("SELECT COUNT(*) FROM messages")
    fun count(): Int

    /**
     * Stage 7 PR E — count of outgoing messages to a given peer.
     * Used by [com.tharmesh.data.MessageRepository.send] to detect
     * whether the about-to-be-inserted message is the *first* the
     * user has ever sent to that peer (drives the
     * [com.tharmesh.data.GrowthMetrics] `chats_started` counter +
     * the post-first-chat viral prompt). Counts both inbound and
     * outbound row directions for the same `peerUserId` would
     * over-count, so the predicate is intentionally tight on
     * `fromUserId == :myUserId AND toUserId == :peerUserId`.
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
         WHERE fromUserId = :myUserId
           AND toUserId = :peerUserId
        """
    )
    fun countOutgoingTo(myUserId: String, peerUserId: String): Int

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE bundleId = :bundleId LIMIT 1")
    fun getByBundleId(bundleId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE peerUserId = :peerUserId ORDER BY timestamp ASC")
    fun getForConversation(peerUserId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE peerUserId = :peerUserId ORDER BY timestamp ASC")
    fun observeConversation(peerUserId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE toUserId = :toUserId OR fromUserId = :toUserId ORDER BY timestamp ASC")
    fun getMessagesForUser(toUserId: String): List<MessageEntity>

    @Query(
        """
        UPDATE messages
           SET status = :status,
               sentAt = CASE WHEN :status = 'SENT' THEN :ts ELSE sentAt END,
               deliveredAt = CASE WHEN :status = 'DELIVERED' THEN :ts ELSE deliveredAt END,
               readAt = CASE WHEN :status = 'READ' THEN :ts ELSE readAt END
         WHERE id = :id
        """
    )
    fun updateStatusById(id: Long, status: String, ts: Long)

    @Query(
        """
        UPDATE messages
           SET status = :status,
               sentAt = CASE WHEN :status = 'SENT' THEN :ts ELSE sentAt END,
               deliveredAt = CASE WHEN :status = 'DELIVERED' THEN :ts ELSE deliveredAt END,
               readAt = CASE WHEN :status = 'READ' THEN :ts ELSE readAt END
         WHERE bundleId = :bundleId
        """
    )
    fun updateStatusByBundleId(bundleId: String, status: String, ts: Long)

    /**
     * Atomic monotonic status advance. Only applies the update if the target rank is
     * strictly higher than the current rank. FAILED has rank -1, so a successful retry
     * (BundleSent → rank 1, BundleDelivered → rank 2, BundleRead → rank 3) can promote
     * a FAILED row forward; this is an advance, not a regression, and is intentional
     * because [pendingOutbound] includes FAILED rows in the store-and-forward queue.
     * Returns the number of rows affected so the caller can skip the conversation bump
     * when the update was a no-op (e.g. a stale BundleSent arriving after BundleAcked).
     * Prevents the read-then-write TOCTOU race when multiple mesh events fire concurrently.
     */
    @Query(
        """
        UPDATE messages
           SET status = :status,
               sentAt = CASE WHEN :status = 'SENT' THEN :ts ELSE sentAt END,
               deliveredAt = CASE WHEN :status = 'DELIVERED' THEN :ts ELSE deliveredAt END,
               readAt = CASE WHEN :status = 'READ' THEN :ts ELSE readAt END
         WHERE bundleId = :bundleId
           AND (CASE :status
                  WHEN 'QUEUED' THEN 0
                  WHEN 'SENDING' THEN 1
                  WHEN 'SENT' THEN 2
                  WHEN 'DELIVERED' THEN 3
                  WHEN 'READ' THEN 4
                  ELSE -1
                END)
             > (CASE status
                  WHEN 'QUEUED' THEN 0
                  WHEN 'SENDING' THEN 1
                  WHEN 'SENT' THEN 2
                  WHEN 'DELIVERED' THEN 3
                  WHEN 'READ' THEN 4
                  ELSE -1
                END)
        """
    )
    fun advanceStatusByBundleId(bundleId: String, status: String, ts: Long): Int

    /**
     * Mark all messages from peerUserId (incoming) as READ with ts, returning the bundleIds
     * that just flipped to READ so the caller can emit READ ack frames.
     */
    @Query(
        """
        SELECT bundleId FROM messages
         WHERE peerUserId = :peerUserId
           AND fromUserId = :peerUserId
           AND status != 'READ'
           AND bundleId IS NOT NULL
        """
    )
    fun pendingReadBundleIds(peerUserId: String): List<String>

    @Query(
        """
        UPDATE messages
           SET status = 'READ', readAt = :ts
         WHERE peerUserId = :peerUserId
           AND fromUserId = :peerUserId
           AND status != 'READ'
        """
    )
    fun markIncomingRead(peerUserId: String, ts: Long)

    /**
     * Outbound messages that have NOT yet been DELIVERED/READ — the store-and-forward
     * retry loop re-broadcasts these every tick in case a peer came back online.
     * SENDING is included because a device that crashes between [Transport.send]
     * accepting the payload and Nearby firing PayloadSent would otherwise wedge the
     * row; on next launch the retry sweep re-broadcasts and advances it forward.
     */
    @Query(
        """
        SELECT * FROM messages
         WHERE fromUserId = :myUserId
           AND (status = 'QUEUED' OR status = 'SENDING' OR status = 'SENT' OR status = 'FAILED')
           AND bundleId IS NOT NULL
         ORDER BY timestamp ASC
         LIMIT 50
        """
    )
    fun pendingOutbound(myUserId: String): List<MessageEntity>

    /**
     * Flip a QUEUED-or-SENDING row to FAILED on a transport-layer send error. Skips
     * rows that are already past SENDING (SENT/DELIVERED/READ) so a late Error on a
     * retry doesn't regress a successfully-delivered message. Returns rows affected.
     */
    @Query(
        """
        UPDATE messages
           SET status = 'FAILED'
         WHERE bundleId = :bundleId
           AND (status = 'QUEUED' OR status = 'SENDING')
        """
    )
    fun markFailedIfStillInFlight(bundleId: String): Int
}

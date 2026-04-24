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
     */
    @Query(
        """
        SELECT * FROM messages
         WHERE fromUserId = :myUserId
           AND (status = 'QUEUED' OR status = 'SENT' OR status = 'FAILED')
           AND bundleId IS NOT NULL
         ORDER BY timestamp ASC
         LIMIT 50
        """
    )
    fun pendingOutbound(myUserId: String): List<MessageEntity>
}

package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversationEntity: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY lastTimestamp DESC")
    fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY lastTimestamp DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE userId = :userId LIMIT 1")
    fun getByUserId(userId: String): ConversationEntity?

    /**
     * Update the last-message summary ONLY when the incoming timestamp is at least as new
     * as the stored one. Prevents status updates for an older message from regressing the
     * conversation row back to older body/ts.
     */
    @Query(
        """
        UPDATE conversations
           SET lastMessage = :lastMessage,
               lastTimestamp = :lastTimestamp,
               lastMessageStatus = :lastStatus
         WHERE userId = :userId
           AND :lastTimestamp >= lastTimestamp
        """
    )
    fun setLastMessage(userId: String, lastMessage: String, lastTimestamp: Long, lastStatus: String): Int

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE userId = :userId")
    fun incrementUnread(userId: String): Int

    @Query("UPDATE conversations SET unreadCount = 0 WHERE userId = :userId")
    fun resetUnread(userId: String): Int
}

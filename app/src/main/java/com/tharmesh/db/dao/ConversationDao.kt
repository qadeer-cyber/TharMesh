package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.ConversationEntity

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversationEntity: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY lastTs DESC")
    fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE convoId = :convoId LIMIT 1")
    fun getByConvoId(convoId: String): ConversationEntity?

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE convoId = :convoId")
    fun incrementUnread(convoId: String): Int

    @Query("UPDATE conversations SET unreadCount = 0 WHERE convoId = :convoId")
    fun clearUnread(convoId: String): Int
}

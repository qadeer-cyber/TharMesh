package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(messageEntity: MessageEntity)

    @Query("SELECT * FROM messages WHERE toUserId = :toUserId OR fromUserId = :toUserId ORDER BY timestamp ASC")
    suspend fun getMessagesForUser(toUserId: String): List<MessageEntity>
}

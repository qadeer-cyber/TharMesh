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

    @Query("SELECT * FROM conversations ORDER BY lastTimestamp DESC")
    fun getAll(): List<ConversationEntity>
}

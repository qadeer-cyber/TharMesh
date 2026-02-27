package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(messageEntity: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE convoId = :convoId ORDER BY ts ASC")
    fun getMessagesForConvo(convoId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE bundleId = :bundleId")
    fun updateStatusByBundleId(bundleId: String, status: String): Int
}

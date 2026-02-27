package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val convoId: String,
    val fromUserId: String,
    val toUserId: String,
    val ciphertext: String,
    val ts: Long,
    val status: String,
    val bundleId: String
)

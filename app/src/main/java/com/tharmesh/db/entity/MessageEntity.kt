package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fromUserId: String,
    val toUserId: String,
    val body: String,
    val status: String,
    val timestamp: Long
)

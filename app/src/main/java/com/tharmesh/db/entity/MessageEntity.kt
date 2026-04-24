package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["peerUserId", "timestamp"]),
        Index(value = ["bundleId"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val fromUserId: String,
    val toUserId: String,

    val peerUserId: String,

    val body: String,
    val status: String,
    val timestamp: Long,

    val bundleId: String? = null,
    val replyToId: Long? = null,
    val replyToPreview: String? = null,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val readAt: Long? = null
)

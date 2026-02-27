package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val userId: String,
    val title: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int
)

package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val convoId: String,
    val title: String,
    val lastMessage: String,
    val lastTs: Long,
    val unreadCount: Int
)

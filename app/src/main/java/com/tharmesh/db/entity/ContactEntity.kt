package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val userId: String,
    val displayName: String,
    val publicKey: String,
    val addedAt: Long,
    val lastSeen: Long
)

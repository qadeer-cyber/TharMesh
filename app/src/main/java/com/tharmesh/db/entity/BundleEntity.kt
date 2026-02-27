package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bundles")
data class BundleEntity(
    @PrimaryKey
    val bundleId: String,
    val toUserId: String,
    val fromUserId: String,
    val payloadCiphertext: String,
    val createdAt: Long,
    val expiresAt: Long,
    val hopCount: Int,
    val maxHops: Int,
    val status: String,
    val attemptCount: Int,
    val nextRetryAt: Long,
    val lastAttemptAt: Long
)

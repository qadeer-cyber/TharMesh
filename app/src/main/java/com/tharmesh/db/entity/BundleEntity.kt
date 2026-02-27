package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bundles")
data class BundleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bundleId: String,
    val toUserId: String,
    val payload: String,
    val createdAt: Long
)

package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent mirror of [com.tharmesh.dtn.MeshBundle]. The in-memory cache in
 * [com.tharmesh.dtn.MeshEngine] is a working set; this table is the source of truth
 * for bundles that must survive process death. [bundleId] is the primary key so
 * inserts are idempotent across restarts and across both origination paths (local
 * queueText) and reception paths (first-arrival handleBundle / forwardBundle).
 */
@Entity(tableName = "bundles")
data class BundleEntity(
    @PrimaryKey val bundleId: String,
    val srcId: String,
    val destId: String,
    val payloadCiphertext: String,
    val ttlUntil: Long,
    val hopsLeft: Int,
    val signature: String,
    val status: String,
    val createdAt: Long
)

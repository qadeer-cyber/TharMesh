package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.BundleEntity

/**
 * CRUD for the persistent bundle store. Every mutation path in
 * [com.tharmesh.dtn.MeshEngine] that changes the in-memory cache must also route
 * through this DAO so the engine's working set and the on-disk source of truth
 * stay in sync across process death.
 *
 * Idempotency: [upsert] uses REPLACE on the [BundleEntity.bundleId] primary key, so
 * duplicate receive paths (e.g. relay chain feeding the same bundle twice) do not
 * create duplicate rows.
 */
@Dao
interface BundleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(bundle: BundleEntity): Long

    /**
     * Load every non-expired bundle. Called on [com.tharmesh.dtn.MeshEngine.start]
     * to rehydrate the in-memory cache after a cold start.
     */
    @Query("SELECT * FROM bundles WHERE ttlUntil >= :nowMs ORDER BY createdAt ASC")
    fun loadActive(nowMs: Long): List<BundleEntity>

    /**
     * Periodic sweep: drop every row whose TTL has passed. Called from the
     * repository's store-and-forward tick and on [com.tharmesh.dtn.MeshEngine.start].
     */
    @Query("DELETE FROM bundles WHERE ttlUntil < :nowMs")
    fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM bundles WHERE bundleId = :bundleId")
    fun deleteByBundleId(bundleId: String): Int

    @Query("UPDATE bundles SET status = :status WHERE bundleId = :bundleId")
    fun updateStatus(bundleId: String, status: String): Int

    @Query("SELECT COUNT(*) FROM bundles")
    fun count(): Int
}

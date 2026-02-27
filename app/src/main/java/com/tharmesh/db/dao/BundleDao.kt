package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.BundleEntity

@Dao
interface BundleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(bundleEntity: BundleEntity): Long

    @Query("SELECT * FROM bundles WHERE status != 'EXPIRED' ORDER BY createdAt DESC")
    fun getActive(): List<BundleEntity>

    @Query("UPDATE bundles SET status = :status WHERE bundleId = :bundleId")
    fun updateStatus(bundleId: String, status: String): Int
}

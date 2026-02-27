package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.BundleEntity

@Dao
interface BundleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bundleEntity: BundleEntity)

    @Query("SELECT * FROM bundles ORDER BY createdAt DESC")
    suspend fun getAll(): List<BundleEntity>
}

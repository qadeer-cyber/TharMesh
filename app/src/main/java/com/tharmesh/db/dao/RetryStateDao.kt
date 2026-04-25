// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.RetryStateEntity

@Dao
interface RetryStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: RetryStateEntity): Long

    @Query("SELECT * FROM retry_state")
    fun loadAll(): List<RetryStateEntity>

    @Query("DELETE FROM retry_state WHERE bundleId = :bundleId")
    fun deleteByBundleId(bundleId: String): Int

    @Query("DELETE FROM retry_state")
    fun deleteAll(): Int

    @Query("UPDATE retry_state SET priority = :priority WHERE bundleId = :bundleId")
    fun updatePriority(bundleId: String, priority: Int): Int
}

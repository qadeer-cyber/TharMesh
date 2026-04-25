package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.PeerIdentityEntity

/**
 * CRUD for the TOFU (trust-on-first-use) peer identity table. Inserts use
 * [OnConflictStrategy.IGNORE] so the FIRST observed key for a peer is pinned —
 * subsequent inserts with a different key are silently dropped, and the receive
 * path compares the incoming key against [findByUserId] to decide whether to
 * reject the bundle.
 */
@Dao
interface PeerIdentityDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(row: PeerIdentityEntity): Long

    @Query("SELECT * FROM peer_identity WHERE userId = :userId LIMIT 1")
    fun findByUserId(userId: String): PeerIdentityEntity?

    @Query("SELECT COUNT(*) FROM peer_identity")
    fun count(): Int
}

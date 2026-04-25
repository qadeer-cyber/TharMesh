package com.tharmesh.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tharmesh.db.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(contactEntity: ContactEntity): Long

    @Query("SELECT * FROM contacts ORDER BY lastSeen DESC, addedAt DESC")
    fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts ORDER BY lastSeen DESC, addedAt DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId LIMIT 1")
    fun getByUserId(userId: String): ContactEntity?

    /**
     * PR B — remove a contact row by [userId]. Intentionally narrow:
     * conversation history (`conversations` + `messages`) and the TOFU
     * pin in `peer_identity` are NOT touched. The user can re-add the
     * contact later and pick up the same chat thread + verified shield.
     */
    @Query("DELETE FROM contacts WHERE userId = :userId")
    fun deleteByUserId(userId: String): Int
}

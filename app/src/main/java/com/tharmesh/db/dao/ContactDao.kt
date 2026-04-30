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
     * Stage 11.1 — resolve a contact by the fingerprint of its TOFU-pinned
     * public key. Returns the contact row whose userId matches the
     * `peer_identity` row with [fingerprint]. Used by
     * [com.tharmesh.data.MessageRepository.addOrMergeContact] to detect
     * "same physical device, different advertised userId" — e.g. the
     * QR-scanned identity vs. a previously nearby-discovered one — so we
     * can merge into the canonical row instead of creating a duplicate.
     *
     * Returns `null` if no `peer_identity` row pins that fingerprint, or
     * if one does but no `contacts` row exists for that userId (the TOFU
     * pin survives contact deletion by design — see [deleteByUserId]).
     */
    @Query(
        """
        SELECT c.* FROM contacts c
         INNER JOIN peer_identity p ON p.userId = c.userId
         WHERE p.fingerprint = :fingerprint
         LIMIT 1
        """
    )
    fun findByFingerprint(fingerprint: String): ContactEntity?

    /**
     * PR B — remove a contact row by [userId]. Intentionally narrow:
     * conversation history (`conversations` + `messages`) and the TOFU
     * pin in `peer_identity` are NOT touched. The user can re-add the
     * contact later and pick up the same chat thread + verified shield.
     */
    @Query("DELETE FROM contacts WHERE userId = :userId")
    fun deleteByUserId(userId: String): Int
}

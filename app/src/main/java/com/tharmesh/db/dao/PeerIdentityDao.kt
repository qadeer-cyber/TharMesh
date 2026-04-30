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
 *
 * Stage 6.2 — added [markVerified] / [isVerified] / [getVerifiedUserIds] to
 * support QR-based out-of-band verification. Verification *never* mutates the
 * pinned [PeerIdentityEntity.publicKeyBase64]; it only flips the
 * [PeerIdentityEntity.verified] flag (and stamps [verifiedAtMs]) on a row that
 * already binds the same key.
 */
@Dao
interface PeerIdentityDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(row: PeerIdentityEntity): Long

    @Query("SELECT * FROM peer_identity WHERE userId = :userId LIMIT 1")
    fun findByUserId(userId: String): PeerIdentityEntity?

    @Query("SELECT COUNT(*) FROM peer_identity")
    fun count(): Int

    /**
     * Stage 6.2 — marks an existing row verified. Callers MUST first confirm
     * the stored [PeerIdentityEntity.publicKeyBase64] equals the user-presented
     * (e.g. QR-scanned) key before invoking this; the DAO does not re-check.
     * Returns the number of rows updated (0 if no row exists for [userId]).
     */
    @Query("UPDATE peer_identity SET verified = 1, verifiedAtMs = :ts WHERE userId = :userId")
    fun setVerified(userId: String, ts: Long): Int

    /** Stage 6.2 — true iff the peer row exists AND `verified = 1`. */
    @Query("SELECT COUNT(*) FROM peer_identity WHERE userId = :userId AND verified = 1")
    fun isVerified(userId: String): Int

    /**
     * Stage 6.2 — userIds of every peer with `verified = 1`. Used by the Chats
     * home's "Trusted" filter chip; replaces the broader Stage 6.1 fallback
     * that surfaced any TOFU-bound peer.
     */
    @Query("SELECT userId FROM peer_identity WHERE verified = 1")
    fun getVerifiedUserIds(): List<String>

    /**
     * Stage 6.1 — userIds for every TOFU-bound peer. Kept around for
     * back-compat / debug introspection; the Trusted chip now uses
     * [getVerifiedUserIds] instead.
     */
    @Query("SELECT userId FROM peer_identity")
    fun getAllUserIds(): List<String>

    /**
     * Stage 11.1 — full row dump, used by
     * [com.tharmesh.data.IdentityDedupMigration] when grouping existing
     * `contacts` rows by fingerprint to detect pre-migration duplicates.
     */
    @Query("SELECT * FROM peer_identity")
    fun getAll(): List<PeerIdentityEntity>

    /**
     * Stage 11.1 — remove the TOFU pin for [userId] once its contact has
     * been merged into a canonical row under a different userId. Safe to
     * call when no row exists (returns 0). Never called on a userId
     * whose contact row is still live.
     */
    @Query("DELETE FROM peer_identity WHERE userId = :userId")
    fun deleteByUserId(userId: String): Int
}

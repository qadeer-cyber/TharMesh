package com.tharmesh.identity

import com.tharmesh.db.dao.PeerIdentityDao
import com.tharmesh.db.entity.PeerIdentityEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 6.2 — covers [RoomPeerTrustStore.markVerified] / [RoomPeerTrustStore.trustState]
 * across the four flows the UI relies on:
 *
 *   1. verify-then-bind: scan QR before the peer's first signed bundle ever
 *      arrives. The store inserts a row with verified=true; a subsequent
 *      [verdict] with the same key returns Match.
 *   2. bind-then-verify: peer's first signed bundle TOFU-pinned the row with
 *      verified=false; a later QR scan with the same key flips verified=true.
 *   3. mismatch: QR scan with a key that disagrees with the pinned key MUST
 *      NOT overwrite the pin and MUST report Mismatch.
 *   4. already-verified: a second QR scan after a successful verification is
 *      a no-op (idempotent), reporting AlreadyVerified.
 *
 * The fake DAO implements the same first-writer-wins semantics as the Room
 * IGNORE-on-conflict insert plus the [PeerIdentityDao.setVerified] update,
 * so the test exercises the real store logic end-to-end.
 */
class PeerTrustStoreTest {

    private class FakePeerIdentityDao : PeerIdentityDao {
        val rows: MutableMap<String, PeerIdentityEntity> = mutableMapOf()

        override fun insertIfAbsent(row: PeerIdentityEntity): Long {
            // first-writer-wins, matches OnConflictStrategy.IGNORE
            return if (rows.putIfAbsent(row.userId, row) == null) 1L else -1L
        }

        override fun findByUserId(userId: String): PeerIdentityEntity? = rows[userId]

        override fun count(): Int = rows.size

        override fun setVerified(userId: String, ts: Long): Int {
            val row = rows[userId] ?: return 0
            rows[userId] = row.copy(verified = true, verifiedAtMs = ts)
            return 1
        }

        override fun isVerified(userId: String): Int =
            if (rows[userId]?.verified == true) 1 else 0

        override fun getVerifiedUserIds(): List<String> =
            rows.values.filter { it.verified }.map { it.userId }

        override fun getAllUserIds(): List<String> = rows.keys.toList()

        // Stage 11.1 — new DAO methods used by IdentityDedupMigration.
        override fun getAll(): List<PeerIdentityEntity> = rows.values.toList()

        override fun deleteByUserId(userId: String): Int =
            if (rows.remove(userId) != null) 1 else 0
    }

    private fun store(clock: () -> Long = { 1_000L }): Pair<RoomPeerTrustStore, FakePeerIdentityDao> {
        val dao = FakePeerIdentityDao()
        return RoomPeerTrustStore(dao, clock) to dao
    }

    private val keyA = "AAA"
    private val keyB = "BBB"

    @Test
    fun verifyBeforeBind_insertsVerifiedRow() {
        val (s, dao) = store { 7L }
        val r = s.markVerified("alice", keyA)
        assertTrue(r is PeerTrustStore.VerifyResult.Verified)
        val row = dao.findByUserId("alice")
        assertNotNull(row)
        assertEquals(keyA, row!!.publicKeyBase64)
        assertTrue(row.verified)
        assertEquals(7L, row.verifiedAtMs)
        assertEquals(PeerTrustStore.TrustState.Verified, s.trustState("alice"))
    }

    @Test
    fun verifyBeforeBind_thenSignedBundleWithSameKey_returnsMatch() {
        val (s, _) = store()
        s.markVerified("alice", keyA)
        val v = s.verdict("alice", keyA)
        assertEquals(PeerTrustStore.Verdict.Match, v)
        // Verified flag must be preserved across receive-path verdict()
        assertEquals(PeerTrustStore.TrustState.Verified, s.trustState("alice"))
    }

    @Test
    fun verifyBeforeBind_thenSignedBundleWithDifferentKey_returnsMismatch() {
        val (s, _) = store()
        s.markVerified("alice", keyA)
        val v = s.verdict("alice", keyB)
        assertTrue(v is PeerTrustStore.Verdict.Mismatch)
        // The verified pin survives — a malicious second-key bundle does not
        // demote the trust state.
        assertEquals(PeerTrustStore.TrustState.Verified, s.trustState("alice"))
    }

    @Test
    fun bindThenVerify_flipsVerifiedFlag() {
        val (s, dao) = store { 42L }
        // First signed bundle TOFU-pins the key with verified=false
        s.verdict("alice", keyA)
        assertEquals(PeerTrustStore.TrustState.TofuOnly, s.trustState("alice"))
        // QR scan with the same key
        val r = s.markVerified("alice", keyA)
        assertTrue(r is PeerTrustStore.VerifyResult.Verified)
        val row = dao.findByUserId("alice")!!
        assertTrue(row.verified)
        assertEquals(42L, row.verifiedAtMs)
        assertEquals(keyA, row.publicKeyBase64)
        assertEquals(PeerTrustStore.TrustState.Verified, s.trustState("alice"))
    }

    @Test
    fun verifyWithMismatchedKey_doesNotOverwritePin() {
        val (s, dao) = store()
        // Bind keyA via signed bundle
        s.verdict("alice", keyA)
        // User scans the wrong QR (e.g. MITM gave them a different key)
        val r = s.markVerified("alice", keyB)
        assertTrue(r is PeerTrustStore.VerifyResult.Mismatch)
        val row = dao.findByUserId("alice")!!
        assertEquals(keyA, row.publicKeyBase64)
        assertFalse("verified must remain false on mismatch", row.verified)
        assertNull(row.verifiedAtMs)
        assertEquals(PeerTrustStore.TrustState.TofuOnly, s.trustState("alice"))
    }

    @Test
    fun secondVerifySameKey_reportsAlreadyVerified_idempotent() {
        val (s, dao) = store { 100L }
        s.markVerified("alice", keyA)
        val rowBefore = dao.findByUserId("alice")!!
        val r = s.markVerified("alice", keyA)
        assertTrue(r is PeerTrustStore.VerifyResult.AlreadyVerified)
        // verifiedAtMs preserved (not bumped to a new clock value)
        assertEquals(rowBefore.verifiedAtMs, dao.findByUserId("alice")!!.verifiedAtMs)
    }

    @Test
    fun trustState_unknown_forNeverSeenPeer() {
        val (s, _) = store()
        assertEquals(PeerTrustStore.TrustState.Unknown, s.trustState("ghost"))
    }

    @Test
    fun getVerifiedUserIds_includesOnlyVerifiedRows() {
        val (s, dao) = store()
        s.verdict("alice", keyA)             // TOFU only
        s.markVerified("bob", keyB)          // verify-before-bind
        s.verdict("carol", "CCC")            // TOFU only
        s.markVerified("carol", "CCC")       // upgraded to verified
        val verified = dao.getVerifiedUserIds().toSet()
        assertEquals(setOf("bob", "carol"), verified)
        // getAllUserIds still surfaces every TOFU-bound peer
        assertEquals(setOf("alice", "bob", "carol"), dao.getAllUserIds().toSet())
    }
}

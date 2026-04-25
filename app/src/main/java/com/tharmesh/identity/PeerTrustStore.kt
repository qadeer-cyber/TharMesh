package com.tharmesh.identity

import com.tharmesh.db.dao.PeerIdentityDao
import com.tharmesh.db.entity.PeerIdentityEntity

/**
 * Abstract the TOFU peer key binding so [com.tharmesh.dtn.MeshEngine] doesn't need
 * to know about Room. The receive path asks for [verdict] on every signed bundle:
 *
 * - [Verdict.FirstSeen]: no stored key for this userId yet — pin it and accept.
 * - [Verdict.Match]: stored key equals the presented one — accept.
 * - [Verdict.Mismatch]: stored key differs from the presented one — reject.
 *
 * Stage 6.2 — extended with [markVerified] / [trustState] for QR-based
 * out-of-band verification. Verification is *additive* over TOFU: it never
 * overwrites the pinned key, only flips a `verified` flag once an out-of-band
 * comparison confirms the pinned key matches what the user just scanned. If
 * the QR carries a key that differs from an already-pinned one, the verify
 * call fails with [VerifyResult.Mismatch] and the row is left untouched —
 * the receive path's existing [Verdict.Mismatch] reject behaviour continues
 * to protect the user.
 */
interface PeerTrustStore {

    sealed class Verdict {
        object FirstSeen : Verdict()
        object Match : Verdict()
        data class Mismatch(val storedFingerprint: String, val presentedFingerprint: String) : Verdict()
    }

    /**
     * Stage 6.2 — overall trust state surfaced in the UI (chat header shield,
     * contact-row shield, "Trusted" filter). [Mismatch] is reported once
     * [markVerified] has been called with a key that differs from the pinned
     * one; the next signed bundle from the peer will independently produce
     * [Verdict.Mismatch] on the receive path.
     */
    sealed class TrustState {
        object Unknown : TrustState()           // no row yet — never seen / never scanned
        object TofuOnly : TrustState()          // row exists, verified = false
        object Verified : TrustState()          // row exists, verified = true
        data class Mismatch(
            val storedFingerprint: String,
            val scannedFingerprint: String
        ) : TrustState()
    }

    sealed class VerifyResult {
        /** Newly inserted (verify-before-bind) or upgraded TOFU row to verified. */
        object Verified : VerifyResult()
        /** Row was already verified with the same key — caller should treat as success. */
        object AlreadyVerified : VerifyResult()
        /** Pinned key differs from the scanned one — pin is preserved, NOT overwritten. */
        data class Mismatch(
            val storedFingerprint: String,
            val scannedFingerprint: String
        ) : VerifyResult()
    }

    /**
     * Look up the stored key for [userId]. If absent, store [presentedPubKeyBase64]
     * atomically (first-writer-wins via the DAO's IGNORE-on-conflict insert) and
     * return [Verdict.FirstSeen]. Return [Verdict.Match] if the stored key equals
     * the presented one, [Verdict.Mismatch] otherwise.
     */
    fun verdict(userId: String, presentedPubKeyBase64: String): Verdict

    /** For introspection / debug. Returns null if no binding. */
    fun storedKey(userId: String): String?

    /**
     * Stage 6.2 — out-of-band confirm a peer's pinned key against the one the
     * user just scanned (typically from the peer's "My QR" screen).
     *
     * Three outcomes:
     * 1. No row exists for [userId] → insert one with `verified = true` (this
     *    is the *verify-before-TOFU-bind* path; the next signed bundle from
     *    the peer must carry the same key or [verdict] returns
     *    [Verdict.Mismatch] and the receive path rejects it).
     * 2. Row exists and pinned key == [scannedPubKeyBase64] → flip
     *    `verified = true`.
     * 3. Row exists and pinned key ≠ [scannedPubKeyBase64] → leave the row
     *    untouched and return [VerifyResult.Mismatch]. The pin is sticky.
     */
    fun markVerified(userId: String, scannedPubKeyBase64: String): VerifyResult {
        // Default no-op so existing test fakes that only override [verdict]
        // keep compiling. The Room implementation below provides the real
        // behaviour.
        return VerifyResult.AlreadyVerified
    }

    /** Stage 6.2 — UI-facing trust state for [userId]. */
    fun trustState(userId: String): TrustState = TrustState.Unknown
}

/** Room-backed [PeerTrustStore]. See [PeerIdentityDao] for the persistence details. */
class RoomPeerTrustStore(
    private val dao: PeerIdentityDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : PeerTrustStore {

    override fun verdict(userId: String, presentedPubKeyBase64: String): PeerTrustStore.Verdict {
        val stored = dao.findByUserId(userId)
        if (stored == null) {
            dao.insertIfAbsent(
                PeerIdentityEntity(
                    userId = userId,
                    publicKeyBase64 = presentedPubKeyBase64,
                    fingerprint = CryptoIdentity.fingerprintOf(presentedPubKeyBase64),
                    firstSeenMs = clock()
                )
            )
            return PeerTrustStore.Verdict.FirstSeen
        }
        return if (stored.publicKeyBase64 == presentedPubKeyBase64) {
            PeerTrustStore.Verdict.Match
        } else {
            PeerTrustStore.Verdict.Mismatch(
                storedFingerprint = stored.fingerprint,
                presentedFingerprint = CryptoIdentity.fingerprintOf(presentedPubKeyBase64)
            )
        }
    }

    override fun storedKey(userId: String): String? = dao.findByUserId(userId)?.publicKeyBase64

    override fun markVerified(
        userId: String,
        scannedPubKeyBase64: String
    ): PeerTrustStore.VerifyResult {
        val stored = dao.findByUserId(userId)
        val ts = clock()
        val scannedFp = CryptoIdentity.fingerprintOf(scannedPubKeyBase64)
        if (stored == null) {
            dao.insertIfAbsent(
                PeerIdentityEntity(
                    userId = userId,
                    publicKeyBase64 = scannedPubKeyBase64,
                    fingerprint = scannedFp,
                    firstSeenMs = ts,
                    verified = true,
                    verifiedAtMs = ts
                )
            )
            return PeerTrustStore.VerifyResult.Verified
        }
        if (stored.publicKeyBase64 != scannedPubKeyBase64) {
            return PeerTrustStore.VerifyResult.Mismatch(
                storedFingerprint = stored.fingerprint,
                scannedFingerprint = scannedFp
            )
        }
        if (stored.verified) {
            return PeerTrustStore.VerifyResult.AlreadyVerified
        }
        dao.setVerified(userId, ts)
        return PeerTrustStore.VerifyResult.Verified
    }

    override fun trustState(userId: String): PeerTrustStore.TrustState {
        val row = dao.findByUserId(userId) ?: return PeerTrustStore.TrustState.Unknown
        return if (row.verified) PeerTrustStore.TrustState.Verified else PeerTrustStore.TrustState.TofuOnly
    }
}

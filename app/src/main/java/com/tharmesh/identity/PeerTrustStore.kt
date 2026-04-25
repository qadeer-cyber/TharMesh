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
 */
interface PeerTrustStore {

    sealed class Verdict {
        object FirstSeen : Verdict()
        object Match : Verdict()
        data class Mismatch(val storedFingerprint: String, val presentedFingerprint: String) : Verdict()
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
}

package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent trust-on-first-use binding of a peer [userId] to the Base64(X.509 DER)
 * public key we first saw them sign with. The binding is sticky: if a later
 * bundle from the same [userId] carries a different public key, the receive
 * path rejects it (see [com.tharmesh.identity.PeerTrustStore] and
 * [com.tharmesh.dtn.MeshEngine] signature verification).
 *
 * Stage 6.2 — extended with [verified] / [verifiedAtMs] for QR-based out-of-band
 * key verification. A row can be inserted with [verified] = true *before* the
 * peer's first signed bundle arrives ("verify-before-TOFU-bind"); subsequent
 * signed bundles still go through the standard Match/Mismatch verdict path so
 * the QR-scanned pin is never bypassed.
 */
@Entity(tableName = "peer_identity")
data class PeerIdentityEntity(
    @PrimaryKey val userId: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val firstSeenMs: Long,
    val verified: Boolean = false,
    val verifiedAtMs: Long? = null
)

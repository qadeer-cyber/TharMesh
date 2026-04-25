package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent trust-on-first-use binding of a peer [userId] to the Base64(X.509 DER)
 * public key we first saw them sign with. The binding is sticky: if a later
 * bundle from the same [userId] carries a different public key, the receive
 * path rejects it (see [com.tharmesh.identity.PeerTrustStore] and
 * [com.tharmesh.dtn.MeshEngine] signature verification).
 */
@Entity(tableName = "peer_identity")
data class PeerIdentityEntity(
    @PrimaryKey val userId: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val firstSeenMs: Long
)

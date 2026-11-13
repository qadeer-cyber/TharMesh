// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity

/**
 * Decides how an authenticated account meets an existing mesh identity on this phone.
 * Does not mutate keys or guess from incomplete server data.
 */
object LegacyReconciliation {

    data class Evidence(
        val serverState: String,
        val canonicalMeshPublicKey: String?,
        val localMesh: CryptoIdentity?,
        val explicitResetCommitted: Boolean,
        val cloudDeviceRows: Int,
        val cloudRecoveryBlob: Boolean
    )

    enum class Decision {
        /** Local mesh is authoritative; cloud has no canonical row (admission off). */
        KEEP_LOCAL_AND_USE_CLOUD_SESSION,
        /** Server matches local mesh public key. */
        KEYS_ALREADY_ALIGNED,
        /** Run canonical activation / proof (admission on). */
        REQUIRES_CANONICAL_ACTIVATION,
        /** User must restore from recovery or approve another device. */
        REQUIRES_RECOVERY_OR_ENROLLMENT,
        /** Prior explicit reset: do not treat as recovery of the old key. */
        RESET_PRECLUDES_LEGACY_RESTORE,
        /** Conflicting fingerprints or server key mismatch. */
        CONFLICT
    }

    fun decide(evidence: Evidence): Decision {
        if (evidence.explicitResetCommitted) return Decision.RESET_PRECLUDES_LEGACY_RESTORE
        val localPub = evidence.localMesh?.publicKeyBase64
        val canonical = evidence.canonicalMeshPublicKey?.takeIf { it.isNotBlank() }
        return when (evidence.serverState) {
            "legacy_unknown" -> when {
                localPub == null -> Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
                evidence.cloudDeviceRows > 0 || evidence.cloudRecoveryBlob ->
                    Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
                else -> Decision.KEEP_LOCAL_AND_USE_CLOUD_SESSION
            }
            "active" -> when {
                localPub == null -> Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
                canonical == null -> Decision.CONFLICT
                canonical == localPub -> Decision.KEYS_ALREADY_ALIGNED
                else -> Decision.CONFLICT
            }
            "recovery_required" -> when {
                localPub == null -> Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
                canonical != null && canonical == localPub -> Decision.REQUIRES_CANONICAL_ACTIVATION
                else -> Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            }
            "unprovisioned" -> when {
                localPub == null -> Decision.REQUIRES_CANONICAL_ACTIVATION
                else -> Decision.REQUIRES_CANONICAL_ACTIVATION
            }
            else -> Decision.CONFLICT
        }
    }
}

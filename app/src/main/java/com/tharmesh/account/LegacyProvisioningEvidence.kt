// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity

/** Builds [LegacyReconciliation.Evidence] for provisioning without guessed server fields. */
object LegacyProvisioningEvidence {

    sealed class BuildResult {
        data class Ready(val evidence: LegacyReconciliation.Evidence) : BuildResult()
        object FailClosedConflict : BuildResult()
    }

    fun build(
        cloudAccessPresent: Boolean,
        fetched: DeviceDirectory.AccountEvidence?,
        canonicalKeyFromOutcome: String?,
        localMesh: CryptoIdentity?,
        explicitResetCommitted: Boolean
    ): BuildResult {
        if (cloudAccessPresent && fetched == null) return BuildResult.FailClosedConflict
        val serverState = fetched?.identityState
            ?: if (canonicalKeyFromOutcome == null) "legacy_unknown" else "recovery_required"
        val canonical = canonicalKeyFromOutcome ?: fetched?.meshPublicKey
        val devices = fetched?.deviceCount ?: 0
        val recovery = fetched?.hasRecoveryBlob ?: false
        val reset = explicitResetCommitted || (fetched?.explicitResetCommitted == true)
        return BuildResult.Ready(
            LegacyReconciliation.Evidence(
                serverState = serverState,
                canonicalMeshPublicKey = canonical,
                localMesh = localMesh,
                explicitResetCommitted = reset,
                cloudDeviceRows = devices,
                cloudRecoveryBlob = recovery
            )
        )
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// UNIT — fake evidence, no network.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyReconciliationTest {

    @Test
    fun legacyUnknown_withLocalMesh_keepsLocal() {
        val mesh = CryptoIdentity.generate()
        val d = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "legacy_unknown",
                canonicalMeshPublicKey = null,
                localMesh = mesh,
                explicitResetCommitted = false,
                cloudDeviceRows = 0,
                cloudRecoveryBlob = false
            )
        )
        assertEquals(LegacyReconciliation.Decision.KEEP_LOCAL_AND_USE_CLOUD_SESSION, d)
    }

    @Test
    fun activeMatchingKeys_aligned() {
        val mesh = CryptoIdentity.generate()
        val d = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "active",
                canonicalMeshPublicKey = mesh.publicKeyBase64,
                localMesh = mesh,
                explicitResetCommitted = false,
                cloudDeviceRows = 0,
                cloudRecoveryBlob = false
            )
        )
        assertEquals(LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED, d)
    }

    @Test
    fun explicitReset_blocksLegacyRestore() {
        val d = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "legacy_unknown",
                canonicalMeshPublicKey = null,
                localMesh = CryptoIdentity.generate(),
                explicitResetCommitted = true,
                cloudDeviceRows = 0,
                cloudRecoveryBlob = false
            )
        )
        assertEquals(LegacyReconciliation.Decision.RESET_PRECLUDES_LEGACY_RESTORE, d)
    }

    @Test
    fun evidenceUnavailable_conflict() {
        val d = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "evidence_unavailable",
                canonicalMeshPublicKey = null,
                localMesh = null,
                explicitResetCommitted = false,
                cloudDeviceRows = 0,
                cloudRecoveryBlob = false
            )
        )
        assertEquals(LegacyReconciliation.Decision.CONFLICT, d)
    }

    @Test
    fun activeKeyMismatch_conflict() {
        val mesh = CryptoIdentity.generate()
        val d = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "active",
                canonicalMeshPublicKey = CryptoIdentity.generate().publicKeyBase64,
                localMesh = mesh,
                explicitResetCommitted = false,
                cloudDeviceRows = 0,
                cloudRecoveryBlob = false
            )
        )
        assertEquals(LegacyReconciliation.Decision.CONFLICT, d)
    }
}

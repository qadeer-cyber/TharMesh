// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Category 1 (unit): onboarding failure contracts — no live Google/SMTP.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFailureContractTest {

    @Test
    fun cloudLoginAlone_doesNotReplaceExistingMesh() {
        val local = CryptoIdentity.generate()
        val decision = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "active",
                canonicalMeshPublicKey = CryptoIdentity.generate().publicKeyBase64,
                localMesh = local,
                explicitResetCommitted = false,
                cloudDeviceRows = 1,
                cloudRecoveryBlob = true
            )
        )
        assertEquals(LegacyReconciliation.Decision.CONFLICT, decision)
    }

    @Test
    fun evidenceUnavailable_failsClosed() {
        val d = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "evidence_unavailable",
                canonicalMeshPublicKey = null,
                localMesh = CryptoIdentity.generate(),
                explicitResetCommitted = false,
                cloudDeviceRows = 0,
                cloudRecoveryBlob = false
            )
        )
        assertEquals(LegacyReconciliation.Decision.CONFLICT, d)
    }

    @Test
    fun returningUser_legacyUnknownWithArtifacts_requiresRecoveryNotSilentReplace() {
        val mesh = CryptoIdentity.generate()
        val d = LegacyReconciliation.decide(
            LegacyReconciliation.Evidence(
                serverState = "legacy_unknown",
                canonicalMeshPublicKey = null,
                localMesh = mesh,
                explicitResetCommitted = false,
                cloudDeviceRows = 1,
                cloudRecoveryBlob = false
            )
        )
        assertEquals(LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT, d)
        assertTrue(d != LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED)
    }
}

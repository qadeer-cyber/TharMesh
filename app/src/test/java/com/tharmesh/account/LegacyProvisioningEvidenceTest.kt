// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyProvisioningEvidenceTest {

    @Test
    fun cloudSession_missingFetch_failClosed() {
        val result = LegacyProvisioningEvidence.build(
            cloudAccessPresent = true,
            fetched = null,
            canonicalKeyFromOutcome = null,
            localMesh = CryptoIdentity.generate(),
            explicitResetCommitted = false
        )
        assertEquals(LegacyProvisioningEvidence.BuildResult.FailClosedConflict, result)
    }

    @Test
    fun cloudSession_withEvidence_usesServerCounts() {
        val mesh = CryptoIdentity.generate()
        val fetched = DeviceDirectory.AccountEvidence(
            identityState = "active",
            meshPublicKey = mesh.publicKeyBase64,
            deviceCount = 3,
            activeDeviceCount = 2,
            hasRecoveryBlob = true,
            explicitResetCommitted = false
        )
        val result = LegacyProvisioningEvidence.build(
            cloudAccessPresent = true,
            fetched = fetched,
            canonicalKeyFromOutcome = null,
            localMesh = mesh,
            explicitResetCommitted = false
        ) as LegacyProvisioningEvidence.BuildResult.Ready
        assertEquals(3, result.evidence.cloudDeviceRows)
        assertTrue(result.evidence.cloudRecoveryBlob)
    }

    @Test
    fun offline_noAccess_allowsZeroPlaceholders() {
        val mesh = CryptoIdentity.generate()
        val result = LegacyProvisioningEvidence.build(
            cloudAccessPresent = false,
            fetched = null,
            canonicalKeyFromOutcome = null,
            localMesh = mesh,
            explicitResetCommitted = false
        ) as LegacyProvisioningEvidence.BuildResult.Ready
        assertEquals(0, result.evidence.cloudDeviceRows)
        assertEquals(false, result.evidence.cloudRecoveryBlob)
        assertEquals(LegacyReconciliation.Decision.KEEP_LOCAL_AND_USE_CLOUD_SESSION, LegacyReconciliation.decide(result.evidence))
    }
}

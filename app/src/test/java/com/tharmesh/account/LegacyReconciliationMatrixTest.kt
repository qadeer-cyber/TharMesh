// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LegacyReconciliationMatrixTest(
    private val label: String,
    private val evidence: LegacyReconciliation.Evidence,
    private val expected: LegacyReconciliation.Decision
) {

    @Test
    fun decide() {
        assertEquals(label, expected, LegacyReconciliation.decide(evidence))
    }

    companion object {
        private val meshA = CryptoIdentity.generate()
        private val meshB = CryptoIdentity.generate()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(
                "active_aligned",
                LegacyReconciliation.Evidence(
                    "active",
                    meshA.publicKeyBase64,
                    meshA,
                    false,
                    1,
                    true
                ),
                LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED
            ),
            arrayOf(
                "active_mismatch",
                LegacyReconciliation.Evidence(
                    "active",
                    meshB.publicKeyBase64,
                    meshA,
                    false,
                    2,
                    true
                ),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "explicit_reset",
                LegacyReconciliation.Evidence(
                    "active",
                    meshA.publicKeyBase64,
                    meshA,
                    true,
                    1,
                    true
                ),
                LegacyReconciliation.Decision.RESET_PRECLUDES_LEGACY_RESTORE
            ),
            arrayOf(
                "recovery_required_aligned",
                LegacyReconciliation.Evidence(
                    "recovery_required",
                    meshA.publicKeyBase64,
                    meshA,
                    false,
                    0,
                    true
                ),
                LegacyReconciliation.Decision.REQUIRES_CANONICAL_ACTIVATION
            ),
            arrayOf(
                "legacy_unknown_offline",
                LegacyReconciliation.Evidence(
                    "legacy_unknown",
                    null,
                    meshA,
                    false,
                    0,
                    false
                ),
                LegacyReconciliation.Decision.KEEP_LOCAL_AND_USE_CLOUD_SESSION
            ),
            arrayOf(
                "legacy_unknown_cloud_rows",
                LegacyReconciliation.Evidence(
                    "legacy_unknown",
                    null,
                    meshA,
                    false,
                    2,
                    false
                ),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "evidence_unavailable",
                LegacyReconciliation.Evidence(
                    "evidence_unavailable",
                    meshA.publicKeyBase64,
                    meshA,
                    false,
                    0,
                    false
                ),
                LegacyReconciliation.Decision.CONFLICT
            )
        )
    }
}

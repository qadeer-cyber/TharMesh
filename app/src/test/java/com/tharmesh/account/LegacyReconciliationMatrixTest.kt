// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Matrix of server evidence → decision (no network).

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
    fun matrix() {
        assertEquals(label, expected, LegacyReconciliation.decide(evidence))
    }

    companion object {
        private fun mesh() = CryptoIdentity.generate()
        private fun other() = CryptoIdentity.generate()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> {
            val m = mesh()
            val o = other()
            return baseRows(m, o) + specSection6Rows(m, o)
        }

        private fun baseRows(m: CryptoIdentity, o: CryptoIdentity): List<Array<Any>> = listOf(
            arrayOf(
                "evidence_unavailable",
                LegacyReconciliation.Evidence("evidence_unavailable", null, null, false, 0, false),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "reset_overrides",
                LegacyReconciliation.Evidence("legacy_unknown", null, m, true, 0, false),
                LegacyReconciliation.Decision.RESET_PRECLUDES_LEGACY_RESTORE
            ),
            arrayOf(
                "legacy_unknown_no_local",
                LegacyReconciliation.Evidence("legacy_unknown", null, null, false, 0, false),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "legacy_unknown_local_clean",
                LegacyReconciliation.Evidence("legacy_unknown", null, m, false, 0, false),
                LegacyReconciliation.Decision.KEEP_LOCAL_AND_USE_CLOUD_SESSION
            ),
            arrayOf(
                "legacy_unknown_devices",
                LegacyReconciliation.Evidence("legacy_unknown", null, m, false, 1, false),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "legacy_unknown_recovery_blob",
                LegacyReconciliation.Evidence("legacy_unknown", null, m, false, 0, true),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "active_no_local",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, null, false, 0, false),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "active_no_canonical",
                LegacyReconciliation.Evidence("active", null, m, false, 0, false),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "active_aligned",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, m, false, 0, false),
                LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED
            ),
            arrayOf(
                "active_mismatch",
                LegacyReconciliation.Evidence("active", o.publicKeyBase64, m, false, 0, false),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "recovery_required_no_local",
                LegacyReconciliation.Evidence("recovery_required", m.publicKeyBase64, null, false, 0, false),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "recovery_required_canonical_activation",
                LegacyReconciliation.Evidence("recovery_required", m.publicKeyBase64, m, false, 0, false),
                LegacyReconciliation.Decision.REQUIRES_CANONICAL_ACTIVATION
            ),
            arrayOf(
                "recovery_required_wrong_local",
                LegacyReconciliation.Evidence("recovery_required", o.publicKeyBase64, m, false, 0, false),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "unprovisioned_no_local",
                LegacyReconciliation.Evidence("unprovisioned", null, null, false, 0, false),
                LegacyReconciliation.Decision.REQUIRES_CANONICAL_ACTIVATION
            ),
            arrayOf(
                "unprovisioned_with_local",
                LegacyReconciliation.Evidence("unprovisioned", null, m, false, 0, false),
                LegacyReconciliation.Decision.REQUIRES_CANONICAL_ACTIVATION
            ),
            arrayOf(
                "unknown_state",
                LegacyReconciliation.Evidence("mystery", null, m, false, 0, false),
                LegacyReconciliation.Decision.CONFLICT
            )
        )

        /** Spec §6 legacy / historical scenarios (fail-closed where ambiguous). */
        private fun specSection6Rows(m: CryptoIdentity, o: CryptoIdentity): List<Array<Any>> = listOf(
            arrayOf(
                "spec_historical_account_original_key",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, m, false, 2, true),
                LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED
            ),
            arrayOf(
                "spec_historical_without_local_key",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, null, false, 1, true),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "spec_existing_recovery_blob",
                LegacyReconciliation.Evidence("legacy_unknown", null, m, false, 0, true),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "spec_existing_device_record",
                LegacyReconciliation.Evidence("legacy_unknown", null, m, false, 3, false),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "spec_matching_fingerprints",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, m, false, 1, false),
                LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED
            ),
            arrayOf(
                "spec_mismatched_fingerprints",
                LegacyReconciliation.Evidence("active", o.publicKeyBase64, m, false, 1, true),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "spec_explicit_reset",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, m, true, 2, true),
                LegacyReconciliation.Decision.RESET_PRECLUDES_LEGACY_RESTORE
            ),
            arrayOf(
                "spec_missing_evidence",
                LegacyReconciliation.Evidence("evidence_unavailable", null, m, false, 0, false),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "spec_network_timeout",
                LegacyReconciliation.Evidence("evidence_unavailable", null, null, false, 0, false),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "spec_partial_migration",
                LegacyReconciliation.Evidence("recovery_required", null, m, false, 1, false),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            ),
            arrayOf(
                "spec_previously_activated_account",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, m, false, 4, true),
                LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED
            ),
            arrayOf(
                "spec_original_device_offline",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, m, false, 2, true),
                LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED
            ),
            arrayOf(
                "spec_new_phone_takeover_attempt",
                LegacyReconciliation.Evidence("active", m.publicKeyBase64, o, false, 2, true),
                LegacyReconciliation.Decision.CONFLICT
            ),
            arrayOf(
                "spec_conflicting_recovery_state",
                LegacyReconciliation.Evidence("recovery_required", m.publicKeyBase64, o, false, 1, true),
                LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
            )
        )
    }
}

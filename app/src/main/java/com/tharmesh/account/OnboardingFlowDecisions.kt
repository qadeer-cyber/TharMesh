// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Decisions shared by AccountFlowActivity and Category-2 integration tests.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity

/**
 * UI / flow outcomes for onboarding without coupling tests to Activity layout inflation.
 * [screen] mirrors [com.tharmesh.ui.account.AccountFlowActivity.Screen] names.
 */
object OnboardingFlowDecisions {

    enum class Screen {
        GOOGLE, EMAIL_OTP, SECURE_IDENTITY, LEGACY_RECONCILE, OFFLINE, DEVICES, SIGN_IN
    }

    data class Outcome(val screen: Screen, val meshPreserved: Boolean, val statusReason: String? = null)

    fun afterGoogleCancel(): Outcome = Outcome(Screen.GOOGLE, meshPreserved = true, statusReason = "google_cancel")

    fun afterOtpFailure(reason: String): Outcome =
        Outcome(Screen.EMAIL_OTP, meshPreserved = true, statusReason = reason)

    fun afterProvisioning(
        result: IdentityProvisioning.Result,
        stagedBefore: CryptoIdentity?,
        stagedAfter: CryptoIdentity?
    ): Outcome {
        val preserved = stagedBefore == null || stagedBefore.publicKeyBase64 == stagedAfter?.publicKeyBase64
        return when (result) {
            is IdentityProvisioning.Result.Activated ->
                Outcome(Screen.SECURE_IDENTITY, meshPreserved = preserved, statusReason = "activated")
            is IdentityProvisioning.Result.RecoveryRequired ->
                Outcome(Screen.LEGACY_RECONCILE, meshPreserved = preserved, statusReason = "recovery_required")
            IdentityProvisioning.Result.Retry ->
                Outcome(Screen.SECURE_IDENTITY, meshPreserved = preserved, statusReason = "retry")
        }
    }

    fun afterSignOut(hadMesh: Boolean): Outcome =
        Outcome(Screen.SIGN_IN, meshPreserved = true, statusReason = if (hadMesh) "signed_out_local_mesh_kept" else "signed_out")

    fun afterAccountChange(previousMesh: CryptoIdentity?, newAccount: Boolean): Outcome {
        val preserved = previousMesh != null
        return Outcome(Screen.SECURE_IDENTITY, meshPreserved = preserved, statusReason = if (newAccount) "account_change" else "account_resumed")
    }

    fun offlineReturningUser(decision: LegacyReconciliation.Decision): Outcome {
        val preserved = decision != LegacyReconciliation.Decision.CONFLICT
        val screen = when (decision) {
            LegacyReconciliation.Decision.KEEP_LOCAL_AND_USE_CLOUD_SESSION -> Screen.OFFLINE
            LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED -> Screen.OFFLINE
            else -> Screen.LEGACY_RECONCILE
        }
        return Outcome(screen, meshPreserved = preserved, statusReason = decision.name)
    }
}

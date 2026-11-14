// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Category 2: enrollment UX outcomes without Activity inflation.

package com.tharmesh.account

/**
 * Maps trusted-cloud enrollment edge responses to UI phases.
 * [DeviceListPresentation] still governs when a row may read as "linked".
 */
object EnrollmentFlowDecisions {

    enum class Phase {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED,
        BACKEND_ERROR,
    }

    data class Outcome(
        val phase: Phase,
        /** Mirrors AccountFlowActivity status strings / failure reasons where applicable. */
        val statusReason: String,
        val cloudEnrollmentConfirmed: Boolean = false,
    )

    fun afterBegin(call: AuthCall): Outcome = when (call) {
        is AuthCall.Ok -> Outcome(Phase.PENDING, "enrollment_pending", cloudEnrollmentConfirmed = false)
        is AuthCall.Failed -> classifyFailure(call.reason, defaultPhase = Phase.BACKEND_ERROR)
        else -> Outcome(Phase.BACKEND_ERROR, "unexpected_auth_call", cloudEnrollmentConfirmed = false)
    }

    fun afterApprove(call: AuthCall, nowEpochSec: Long, expiresEpochSec: Long): Outcome = when (call) {
        is AuthCall.Ok ->
            if (expiresEpochSec > 0L && nowEpochSec > expiresEpochSec) {
                Outcome(Phase.EXPIRED, "enrollment_expired", cloudEnrollmentConfirmed = false)
            } else {
                Outcome(Phase.APPROVED, "enrollment_approved", cloudEnrollmentConfirmed = false)
            }
        is AuthCall.Failed -> classifyFailure(call.reason, defaultPhase = Phase.REJECTED)
        else -> Outcome(Phase.REJECTED, "unexpected_auth_call", cloudEnrollmentConfirmed = false)
    }

    fun afterFinish(call: AuthCall): Outcome = when (call) {
        is AuthCall.Ok -> Outcome(Phase.APPROVED, "enrollment_finished", cloudEnrollmentConfirmed = true)
        is AuthCall.Failed -> classifyFailure(call.reason, defaultPhase = Phase.BACKEND_ERROR)
        else -> Outcome(Phase.BACKEND_ERROR, "unexpected_auth_call", cloudEnrollmentConfirmed = false)
    }

    fun deviceListLabel(cloudDevicesConfirmed: Boolean?, revoked: Boolean, enrollment: Outcome): String {
        if (!mayShowLinked(enrollment, cloudDevicesConfirmed)) {
            return when (enrollment.phase) {
                Phase.PENDING -> DeviceListPresentation.LABEL_LOADING
                Phase.APPROVED -> DeviceListPresentation.LABEL_UNCONFIRMED
                Phase.REJECTED, Phase.EXPIRED, Phase.BACKEND_ERROR -> DeviceListPresentation.LABEL_UNCONFIRMED
            }
        }
        return DeviceListPresentation.rowLabel(cloudDevicesConfirmed, revoked)
    }

    /** Linked is never shown until server directory fetch succeeded and finish/approve confirmed. */
    fun mayShowLinked(enrollment: Outcome, cloudDevicesConfirmed: Boolean?): Boolean {
        if (cloudDevicesConfirmed != true) return false
        if (enrollment.phase != Phase.APPROVED) return false
        return enrollment.cloudEnrollmentConfirmed
    }

    private fun classifyFailure(reason: String, defaultPhase: Phase): Outcome {
        val lower = reason.lowercase()
        val phase = when {
            lower.contains("expired") || reason == "http_410" -> Phase.EXPIRED
            reason == "http_403" || reason == "http_401" || reason == "http_409" -> Phase.REJECTED
            reason == "network" || reason == "cloud_auth_not_configured" || reason == "unauthorized" ->
                Phase.BACKEND_ERROR
            reason.startsWith("http_5") -> Phase.BACKEND_ERROR
            else -> defaultPhase
        }
        return Outcome(phase, reason, cloudEnrollmentConfirmed = false)
    }
}

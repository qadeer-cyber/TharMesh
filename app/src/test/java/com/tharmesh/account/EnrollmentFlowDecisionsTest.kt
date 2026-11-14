// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Category 2: trusted enrollment UX phases (mock HTTP via AuthCall).

package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentFlowDecisionsTest {

    @Test fun begin_ok_isPending_notLinked() {
        val o = EnrollmentFlowDecisions.afterBegin(AuthCall.Ok(AuthTokens("", "", 0)))
        assertEquals(EnrollmentFlowDecisions.Phase.PENDING, o.phase)
        assertFalse(EnrollmentFlowDecisions.mayShowLinked(o, cloudDevicesConfirmed = true))
        assertEquals(
            DeviceListPresentation.LABEL_LOADING,
            EnrollmentFlowDecisions.deviceListLabel(true, false, o)
        )
    }

    @Test fun approve_ok_withoutFinish_staysUnconfirmedOnList() {
        val now = 1_700_000_100L
        val o = EnrollmentFlowDecisions.afterApprove(AuthCall.Ok(AuthTokens("", "", 0)), now, now + 900)
        assertEquals(EnrollmentFlowDecisions.Phase.APPROVED, o.phase)
        assertFalse(EnrollmentFlowDecisions.mayShowLinked(o, cloudDevicesConfirmed = true))
        assertEquals(
            DeviceListPresentation.LABEL_UNCONFIRMED,
            EnrollmentFlowDecisions.deviceListLabel(true, false, o)
        )
    }

    @Test fun finish_ok_mayShowLinkedWhenCloudConfirmed() {
        val o = EnrollmentFlowDecisions.afterFinish(AuthCall.Ok(AuthTokens("", "", 0)))
        assertTrue(EnrollmentFlowDecisions.mayShowLinked(o, cloudDevicesConfirmed = true))
        assertEquals(
            DeviceListPresentation.LABEL_LINKED,
            EnrollmentFlowDecisions.deviceListLabel(true, revoked = false, o)
        )
    }

    @Test fun rejected_http403() {
        val o = EnrollmentFlowDecisions.afterApprove(AuthCall.Failed("http_403"), 0, 0)
        assertEquals(EnrollmentFlowDecisions.Phase.REJECTED, o.phase)
        assertFalse(EnrollmentFlowDecisions.mayShowLinked(o, true))
    }

    @Test fun expired_onApproveWindow() {
        val o = EnrollmentFlowDecisions.afterApprove(AuthCall.Ok(AuthTokens("", "", 0)), 2_000, 1_000)
        assertEquals(EnrollmentFlowDecisions.Phase.EXPIRED, o.phase)
    }

    @Test fun backendError_network() {
        val o = EnrollmentFlowDecisions.afterBegin(AuthCall.Failed("network"))
        assertEquals(EnrollmentFlowDecisions.Phase.BACKEND_ERROR, o.phase)
        assertEquals("network", o.statusReason)
    }
}

package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceListPresentationTest {

    @Test
    fun linkedLabel_onlyWhenCloudConfirmed() {
        assertEquals(DeviceListPresentation.LABEL_LOADING, DeviceListPresentation.rowLabel(null, false))
        assertEquals(DeviceListPresentation.LABEL_UNCONFIRMED, DeviceListPresentation.rowLabel(false, false))
        assertEquals(DeviceListPresentation.LABEL_LINKED, DeviceListPresentation.rowLabel(true, false))
        assertEquals(DeviceListPresentation.LABEL_REVOKED, DeviceListPresentation.rowLabel(true, true))
    }

    @Test
    fun pendingEnrollment_mustNotShowLinked() {
        assertFalse(DeviceListPresentation.mayShowLinkedPendingEnrollment(false))
        assertFalse(DeviceListPresentation.mayShowLinkedPendingEnrollment(null))
        assertTrue(DeviceListPresentation.mayShowLinkedPendingEnrollment(true))
    }
}

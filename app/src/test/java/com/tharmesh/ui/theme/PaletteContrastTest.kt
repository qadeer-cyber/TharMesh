// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.theme

import com.tharmesh.identity.ContactIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UNIT. Contrast math only. No device rendering. */
class PaletteContrastTest {

    @Test
    fun oceanAndRoseSamples_meetWcagAa() {
        for (sample in PaletteContrast.samples()) {
            assertTrue(
                sample.name + " ratio " + PaletteContrast.ratio(sample.foreground, sample.background),
                PaletteContrast.passesAa(sample)
            )
        }
    }

    @Test
    fun truncatedNearbyName_isRejected_fullIdsStay() {
        assertTrue(ContactIdentity.isTruncatedMeshId("user-"))
        assertTrue(ContactIdentity.isTruncatedMeshId("user-ab"))
        assertFalse(ContactIdentity.isTruncatedMeshId("user-a416834d"))
        assertFalse(ContactIdentity.isTruncatedMeshId("acct-uuid-value"))
    }
}

package com.tharmesh.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Stage 5.3 — minimal unit tests for the [PermissionStatus] sealed class.
 * The full [PermissionMonitor] flow involves Android Context / LocationManager
 * / BluetoothAdapter and is exercised by manual on-device QA + the
 * [com.tharmesh.ui.main.MainActivity] banner in the field test plan, not in
 * a Robolectric unit test (which we don't add to keep the build lightweight).
 *
 * What we DO test here is the data-class shape: equality semantics so the
 * banner can suppress redundant rerender, and that PermissionDenied carries
 * the missing-permission list (UI uses it to decide whether to call the
 * runtime permission prompt vs. open the system settings panel).
 */
class PermissionStatusTest {

    @Test
    fun `Ready BluetoothOff and LocationOff are object singletons`() {
        // Singleton equality lets the banner check `last == new` to skip
        // redundant rerender / TextView.setText calls.
        assertEquals(PermissionStatus.Ready, PermissionStatus.Ready)
        assertEquals(PermissionStatus.BluetoothOff, PermissionStatus.BluetoothOff)
        assertEquals(PermissionStatus.LocationOff, PermissionStatus.LocationOff)
        // …and the three are NOT equal to each other.
        assertNotEquals(PermissionStatus.Ready, PermissionStatus.BluetoothOff)
        assertNotEquals(PermissionStatus.BluetoothOff, PermissionStatus.LocationOff)
    }

    @Test
    fun `PermissionDenied equality is structural over the missing list`() {
        val a = PermissionStatus.PermissionDenied(listOf("X", "Y"))
        val b = PermissionStatus.PermissionDenied(listOf("X", "Y"))
        val c = PermissionStatus.PermissionDenied(listOf("X"))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `PermissionDenied surfaces the missing list to callers`() {
        val s = PermissionStatus.PermissionDenied(listOf("android.permission.BLUETOOTH_SCAN"))
        assertEquals(1, s.missing.size)
        assertEquals("android.permission.BLUETOOTH_SCAN", s.missing[0])
    }
}

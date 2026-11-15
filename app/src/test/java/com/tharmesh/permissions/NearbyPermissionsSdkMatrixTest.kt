package com.tharmesh.permissions

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPermissionsSdkMatrixTest {

  @Test
  fun api31Plus_usesScanConnectAdvertise_notLegacyBluetooth() {
    val perms = NearbyPermissions.requiredForSdk(31).toSet()
    assertTrue(perms.contains("android.permission.BLUETOOTH_SCAN"))
    assertTrue(perms.contains("android.permission.BLUETOOTH_CONNECT"))
    assertTrue(perms.contains("android.permission.BLUETOOTH_ADVERTISE"))
    assertFalse(perms.contains(Manifest.permission.BLUETOOTH))
    assertFalse(perms.contains(Manifest.permission.BLUETOOTH_ADMIN))
    assertTrue(perms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
  }

  @Test
  fun legacyApi_usesClassicBluetoothAndCoarseLocation() {
    val perms = NearbyPermissions.requiredForSdk(30)
    assertTrue(perms.contains(Manifest.permission.BLUETOOTH))
    assertTrue(perms.contains(Manifest.permission.BLUETOOTH_ADMIN))
    assertTrue(perms.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    assertFalse(perms.toSet().contains("android.permission.BLUETOOTH_SCAN"))
  }

  @Test
  fun matrixDiffersBetweenLegacyAndS() {
    val legacy = NearbyPermissions.requiredForSdk(29).toSet()
    val modern = NearbyPermissions.requiredForSdk(33).toSet()
    assertFalse(legacy == modern)
  }

  @Test
  fun api33_includesNearbyWifiDevices() {
    val perms = NearbyPermissions.requiredForSdk(33).toSet()
    assertTrue(perms.contains("android.permission.NEARBY_WIFI_DEVICES"))
  }

  @Test
  fun api34_api35_matchApi33NearbyWifiDevices() {
    val p33 = NearbyPermissions.requiredForSdk(33).toSet()
    assertArrayEquals(
      NearbyPermissions.requiredForSdk(34).sorted().toTypedArray(),
      NearbyPermissions.requiredForSdk(35).sorted().toTypedArray()
    )
    assertTrue(NearbyPermissions.requiredForSdk(34).toSet().containsAll(p33))
  }
}
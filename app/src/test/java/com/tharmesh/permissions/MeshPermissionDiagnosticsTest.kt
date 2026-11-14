package com.tharmesh.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshPermissionDiagnosticsTest {

  @Test
  fun mapsEachPermissionStatusToDistinctBanner() {
    assertEquals(MeshPermissionDiagnostics.BANNER_READY, MeshPermissionDiagnostics.bannerStringName(PermissionStatus.Ready))
    assertEquals(
      MeshPermissionDiagnostics.BANNER_PERMISSIONS,
      MeshPermissionDiagnostics.bannerStringName(
        PermissionStatus.PermissionDenied(listOf("android.permission.BLUETOOTH_SCAN"))
      )
    )
    assertEquals(
      MeshPermissionDiagnostics.BANNER_BLUETOOTH_OFF,
      MeshPermissionDiagnostics.bannerStringName(PermissionStatus.BluetoothOff)
    )
    assertEquals(
      MeshPermissionDiagnostics.BANNER_LOCATION_OFF,
      MeshPermissionDiagnostics.bannerStringName(PermissionStatus.LocationOff)
    )
  }
}
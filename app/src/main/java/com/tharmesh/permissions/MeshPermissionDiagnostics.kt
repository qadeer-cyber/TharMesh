// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.permissions

/**
 * Maps [PermissionStatus] to stable diagnostic labels for banners and exports.
 * Labels are user-visible copy keys — must stay aligned with `strings.xml` banners.
 */
object MeshPermissionDiagnostics {

    enum class Reason {
        READY,
        PERMISSIONS_REVOKED,
        BLUETOOTH_OFF,
        LOCATION_OFF
    }

    /** Banner string resource names (not Android R ids — JVM-testable contract). */
    const val BANNER_READY: String = ""
    const val BANNER_PERMISSIONS: String = "banner_perm_missing"
    const val BANNER_BLUETOOTH_OFF: String = "banner_bluetooth_off"
    const val BANNER_LOCATION_OFF: String = "banner_location_off"

    fun classify(status: PermissionStatus): Reason = when (status) {
        PermissionStatus.Ready -> Reason.READY
        is PermissionStatus.PermissionDenied -> Reason.PERMISSIONS_REVOKED
        PermissionStatus.BluetoothOff -> Reason.BLUETOOTH_OFF
        PermissionStatus.LocationOff -> Reason.LOCATION_OFF
    }

    fun bannerStringName(status: PermissionStatus): String = when (classify(status)) {
        Reason.READY -> BANNER_READY
        Reason.PERMISSIONS_REVOKED -> BANNER_PERMISSIONS
        Reason.BLUETOOTH_OFF -> BANNER_BLUETOOTH_OFF
        Reason.LOCATION_OFF -> BANNER_LOCATION_OFF
    }
}
package com.tharmesh.permissions

import android.Manifest
import android.os.Build

/**
 * Canonical list of runtime permissions needed for Google Nearby Connections at different
 * Android API levels. Callers should pass [required] into
 * `ActivityCompat.requestPermissions(...)`.
 *
 * We hard-code the Android 12+ Bluetooth permission strings rather than referencing
 * `Manifest.permission.BLUETOOTH_SCAN` etc. because those constants only exist on
 * compileSdk 31+ and this project currently targets compileSdk 30 (to stay compatible
 * with older Android Studio versions on macOS High Sierra).
 */
object NearbyPermissions {
    private const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"

    // Build.VERSION_CODES.S = 31 on real devices, but the constant is only
    // defined in compileSdk 31+. Inline the integer to stay compatible.
    private const val SDK_S: Int = 31

    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= SDK_S) {
            arrayOf(
                BLUETOOTH_ADVERTISE,
                BLUETOOTH_CONNECT,
                BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }
}

package com.tharmesh.permissions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Stage 5.3 — Permission hardening. Snapshot of the runtime conditions the
 * mesh transport actually needs to come up. Computed synchronously by
 * [PermissionMonitor.snapshot]; surfaced in [com.tharmesh.ui.main.MainActivity]
 * as a banner with a "Retry" button so the mesh never silently fails for the
 * user.
 *
 * Order of precedence (the banner shows the first match):
 *  1. [PermissionDenied]  — runtime permission is not granted
 *  2. [BluetoothOff]      — permissions OK but BluetoothAdapter is disabled
 *  3. [LocationOff]       — permissions OK but Location services are off
 *  4. [Ready]             — everything green; mesh can start
 *
 * We DELIBERATELY do not return multiple states at once. The user can only fix
 * one thing at a time, and stacking banners is noisy. Once they've fixed the
 * first issue, the next call to [PermissionMonitor.snapshot] surfaces the
 * next-blocking issue (if any).
 */
sealed class PermissionStatus {
    /** All required permissions granted, BT on, Location on. Mesh can start. */
    object Ready : PermissionStatus()

    /** A runtime permission needs to be requested. [missing] is non-empty. */
    data class PermissionDenied(val missing: List<String>) : PermissionStatus()

    /** Permissions granted but the BluetoothAdapter is disabled. */
    object BluetoothOff : PermissionStatus()

    /** Permissions granted but Location services are turned off. */
    object LocationOff : PermissionStatus()
}

/**
 * Stage 5.3 — Synchronous snapshot of the device's mesh-relevant
 * permission/runtime state. No coroutines, no listeners — call from
 * `onResume` of an Activity that hosts the banner. Cheap (<1 ms in practice).
 */
object PermissionMonitor {

    /** Compute the most-blocking [PermissionStatus] for [context]. */
    fun snapshot(context: Context): PermissionStatus {
        val missing = NearbyPermissions.required.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            return PermissionStatus.PermissionDenied(missing)
        }
        if (!isBluetoothOn()) return PermissionStatus.BluetoothOff
        if (!isLocationOn(context)) return PermissionStatus.LocationOff
        return PermissionStatus.Ready
    }

    /** True when the device has Bluetooth and the adapter is enabled. */
    private fun isBluetoothOn(): Boolean {
        // BluetoothAdapter.getDefaultAdapter() is deprecated on SDK 31+ but
        // still works and is the only API available on our compileSdk 30
        // toolchain. Returning true on null (no BT hardware at all) would be
        // misleading; we treat absent hardware as "off" because the user
        // can't fix it but at least the banner is honest.
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    /**
     * True when at least one of the location providers (GPS or network) is
     * enabled. Some OEMs report GPS off but network location on, which is
     * fine for BLE scanning; require both off to flag.
     */
    private fun isLocationOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            // LocationManager.GPS_PROVIDER / NETWORK_PROVIDER are stable across SDKs.
            val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            gps || network
        } catch (ignored: SecurityException) {
            // Should never happen — checking provider enabled-ness doesn't
            // require permissions on any SDK we target. Fall back to "on" so
            // we don't show a misleading banner if the OEM has weird policy.
            true
        }
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tharmesh.TharMeshApp
import com.tharmesh.data.UserPrefs
import com.tharmesh.disaster.DisasterModeController
import kotlinx.coroutines.flow.combine
import com.tharmesh.permissions.NearbyPermissions
import com.tharmesh.permissions.PermissionMonitor
import com.tharmesh.permissions.PermissionStatus
import com.tharmesh.ui.auth.LoginActivity
import com.tharmesh.ui.dashboard.DashboardFragment
import com.tharmesh.ui.devices.DevicesFragment
import com.tharmesh.ui.messages.MessagesFragment
import com.tharmesh.ui.settings.SettingsFragment
import com.tharmesh.ui.status.StatusFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tharmesh.app.R

/**
 * Single-activity shell that hosts the 5 primary screens behind a bottom nav:
 * Home (dashboard) · Devices · Messages · Status · Settings.
 *
 * Stage 5.3 — also owns the global mesh status banner (R.id.mesh_status_banner)
 * which surfaces missing-permission / Bluetooth-off / Location-off / scanning
 * / N-peers state. The banner is updated on every onResume + every directory
 * peer-list change, so the user always knows whether the mesh is alive.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusBanner: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusAction: Button
    private lateinit var disasterBanner: LinearLayout
    private lateinit var disasterText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UserPrefs.hasProfile(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        TharMeshApp.get().ensureMeshStarted()
        requestNearbyPermissionsIfMissing()

        statusBanner = findViewById(R.id.mesh_status_banner)
        statusText = findViewById(R.id.mesh_status_text)
        statusAction = findViewById(R.id.mesh_status_action)
        disasterBanner = findViewById(R.id.disaster_mode_banner)
        disasterText = findViewById(R.id.disaster_mode_text)

        // Stage 6.1 — WhatsApp-style nav structure (Chats / Devices / Alerts /
        // Relay / Settings). Channels (6.4) and Topology (6.5) have not landed
        // yet, so Alerts and Relay are wired to the existing Status and
        // Dashboard fragments respectively as visual placeholders. The bound
        // Fragment for each tab is the one whose data is closest to the future
        // feature surface, so the user's mental model carries forward when 6.4
        // / 6.5 replace the placeholders.
        val nav: BottomNavigationView = findViewById(R.id.bottom_nav)
        nav.setOnNavigationItemSelectedListener { item ->
            val frag: Fragment = when (item.itemId) {
                R.id.nav_chats -> MessagesFragment()
                R.id.nav_devices -> DevicesFragment()
                R.id.nav_alerts -> StatusFragment()
                R.id.nav_relay -> DashboardFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnNavigationItemSelectedListener false
            }
            showFragment(frag)
            true
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_chats
        }

        // Stage 5.3 — keep the banner copy in sync with the live peer count.
        // The directory's nodes Flow is process-singleton (TharMeshApp owns it)
        // so the only thing we need here is to trigger a refresh when it
        // changes. The actual rendering goes through refreshStatusBanner()
        // which also factors in permission state.
        val app = TharMeshApp.get()
        lifecycleScope.launch {
            app.directory.nodes.collectLatest {
                refreshStatusBanner()
            }
        }

        // Stage 6.3 — disaster-mode + battery-low state drive the persistent
        // red banner. Combined so a single collector handles both flips.
        lifecycleScope.launch {
            combine(
                DisasterModeController.enabled,
                DisasterModeController.batteryLow
            ) { on, low -> on to low }.collectLatest { (on, low) ->
                renderDisasterBanner(on, low)
            }
        }
    }

    private fun renderDisasterBanner(enabled: Boolean, batteryLow: Boolean) {
        if (!enabled) {
            disasterBanner.visibility = View.GONE
            return
        }
        disasterText.text = if (batteryLow) {
            getString(R.string.disaster_banner_active_low_battery)
        } else {
            getString(R.string.disaster_banner_active)
        }
        disasterBanner.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // Stage 5.3 — runtime conditions (BT / Location / permissions) can
        // change while we're paused (Settings → toggles), so re-evaluate on
        // every resume rather than relying on the directory flow alone.
        refreshStatusBanner()
        // If the user just flipped a missing permission (or BT/Location)
        // back on while in Settings, kick the mesh in case it failed to
        // start the first time.
        TharMeshApp.get().ensureMeshStarted()
    }

    private fun showFragment(frag: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
    }

    /**
     * Stage 5.3 — render the global mesh status banner. Permission/runtime
     * issues take precedence (they block the mesh entirely); when everything
     * is green we surface the live peer count for connection visibility.
     *
     * The banner is hidden when the mesh is up and at least one peer is
     * connected — at that point the per-screen UI (Dashboard's "Connected to
     * N devices" card, Devices tab list, etc.) carries the signal and the
     * banner would just be visual noise.
     */
    private fun refreshStatusBanner() {
        when (PermissionMonitor.snapshot(this)) {
            is PermissionStatus.PermissionDenied -> {
                showBanner(getString(R.string.banner_perm_missing), getString(R.string.banner_action_retry)) {
                    requestNearbyPermissionsIfMissing()
                }
            }
            is PermissionStatus.BluetoothOff -> {
                showBanner(getString(R.string.banner_bluetooth_off), getString(R.string.banner_action_settings)) {
                    // Try the system Bluetooth settings panel first; fall back
                    // to the generic Settings root if the device doesn't
                    // expose the panel intent.
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }
            is PermissionStatus.LocationOff -> {
                showBanner(getString(R.string.banner_location_off), getString(R.string.banner_action_settings)) {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            is PermissionStatus.Ready -> {
                renderConnectionVisibilityBanner()
            }
        }
    }

    /**
     * Stage 5.3 D2 — connection visibility. When permissions are green, show
     * "Searching…" if no peers, and the connected count otherwise. Hidden
     * once a peer is online (the dashboard card carries the same signal in
     * a richer form, so duplicating it in the banner is just noise).
     */
    private fun renderConnectionVisibilityBanner() {
        val app = TharMeshApp.get()
        val nodes = app.directory.nodes.value
        val onlineCount = nodes.count { it.online }
        if (onlineCount > 0) {
            // Mesh is alive and a peer is connected — let the per-screen UI
            // tell the story; banner is redundant.
            statusBanner.visibility = View.GONE
            return
        }
        val text = if (nodes.isEmpty()) {
            getString(R.string.banner_searching)
        } else {
            // Discovered but not connected yet — phrase it as "no devices found"
            // because the user-relevant property is "can I send right now" and
            // the answer is still no.
            getString(R.string.banner_no_devices)
        }
        showBanner(text, action = null, onAction = null)
    }

    private fun showBanner(text: String, action: String?, onAction: (() -> Unit)?) {
        statusText.text = text
        if (action != null && onAction != null) {
            statusAction.text = action
            statusAction.visibility = View.VISIBLE
            statusAction.setOnClickListener { onAction() }
        } else {
            statusAction.visibility = View.GONE
            statusAction.setOnClickListener(null)
        }
        statusBanner.visibility = View.VISIBLE
    }

    /**
     * Kick off the runtime permission prompt for Nearby / Bluetooth / Location. Does
     * nothing if all required permissions are already granted. Only runtime-sensitive
     * permissions from [NearbyPermissions.required] are requested; the manifest still
     * declares the full set.
     */
    private fun requestNearbyPermissionsIfMissing() {
        val missing = NearbyPermissions.required.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_NEARBY_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NEARBY_PERMS) return
        val denied = permissions.zip(grantResults.toList())
            .any { it.second != PackageManager.PERMISSION_GRANTED }
        if (denied) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perms_needed_title)
                .setMessage(R.string.perms_needed_body)
                .setPositiveButton(R.string.perms_needed_retry) { _, _ ->
                    requestNearbyPermissionsIfMissing()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            // Permissions just flipped to granted. ensureMeshStarted bailed out earlier
            // because perms were missing (it does NOT flip started=true in that case),
            // so calling it again now actually wires the transport.
            TharMeshApp.get().ensureMeshStarted()
        }
        refreshStatusBanner()
    }

    companion object {
        private const val REQ_NEARBY_PERMS = 4011
    }
}

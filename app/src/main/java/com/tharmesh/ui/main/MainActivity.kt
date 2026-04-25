// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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
import com.tharmesh.ui.auth.LoginActivity
import com.tharmesh.ui.contacts.ContactsFragment
import com.tharmesh.ui.dashboard.DashboardFragment
import com.tharmesh.ui.messages.MessagesFragment
import com.tharmesh.ui.settings.SettingsFragment
import com.tharmesh.ui.status.StatusFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tharmesh.app.R

/**
 * Single-activity shell that hosts the 5 primary screens behind a bottom nav.
 *
 * Owns the persistent Disaster Mode banner. The legacy mesh status banner
 * (BT off / Location off / scanning / N peers) was retired in the UI polish
 * pass; the same signal now surfaces as a small warning dot on the mesh
 * icon in the Chats top bar — tap opens an enable-Bluetooth dialog.
 */
class MainActivity : AppCompatActivity() {

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
                R.id.nav_contacts -> ContactsFragment()
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
        // If the user flipped a missing permission (or BT/Location) back on
        // while we were paused, kick the mesh again now that conditions are
        // green; ensureMeshStarted is idempotent.
        TharMeshApp.get().ensureMeshStarted()
    }

    private fun showFragment(frag: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
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
        // The runtime permission dialog does not pause this Activity, so the
        // hosted MessagesFragment's onResume won't fire and the mesh-status
        // warning dot would stay stuck on a stale value. Nudge it directly.
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current is MessagesFragment) {
            current.refreshMeshWarningDot()
        }
    }

    companion object {
        private const val REQ_NEARBY_PERMS = 4011
    }
}

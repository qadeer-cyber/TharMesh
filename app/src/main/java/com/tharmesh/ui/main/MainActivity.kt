package com.tharmesh.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tharmesh.TharMeshApp
import com.tharmesh.data.UserPrefs
import com.tharmesh.permissions.NearbyPermissions
import com.tharmesh.ui.auth.LoginActivity
import com.tharmesh.ui.dashboard.DashboardFragment
import com.tharmesh.ui.devices.DevicesFragment
import com.tharmesh.ui.messages.MessagesFragment
import com.tharmesh.ui.settings.SettingsFragment
import com.tharmesh.ui.status.StatusFragment
import tharmesh.app.R

/**
 * Single-activity shell that hosts the 5 primary screens behind a bottom nav:
 * Home (dashboard) · Devices · Messages · Status · Settings.
 */
class MainActivity : AppCompatActivity() {

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

        val nav: BottomNavigationView = findViewById(R.id.bottom_nav)
        nav.setOnNavigationItemSelectedListener { item ->
            val frag: Fragment = when (item.itemId) {
                R.id.nav_home -> DashboardFragment()
                R.id.nav_devices -> DevicesFragment()
                R.id.nav_messages -> MessagesFragment()
                R.id.nav_status -> StatusFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnNavigationItemSelectedListener false
            }
            showFragment(frag)
            true
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_home
        }
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
    }

    companion object {
        private const val REQ_NEARBY_PERMS = 4011
    }
}

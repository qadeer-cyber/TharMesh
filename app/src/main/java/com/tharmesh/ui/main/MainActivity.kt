package com.tharmesh.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tharmesh.TharMeshApp
import com.tharmesh.data.UserPrefs
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
}

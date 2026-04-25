package com.tharmesh.ui.devices

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import tharmesh.app.R

/**
 * Thin host for [DevicesFragment]. The Devices surface is no longer in the
 * bottom-nav (replaced by Contacts in PR B); this Activity is reachable from
 * the Chats overflow menu so the existing diagnostics + raw nearby-node view
 * stays one tap away without crowding the primary nav.
 */
class DevicesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.devices_container, DevicesFragment())
                .commit()
        }
    }
}

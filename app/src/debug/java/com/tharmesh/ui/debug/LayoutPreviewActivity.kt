// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.debug

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import tharmesh.app.R

/**
 * Debug-only layout viewer. The screen name must be on the whitelist.
 * This activity does not read credentials and does not start registration.
 */
class LayoutPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val night = intent.getBooleanExtra(EXTRA_NIGHT, false)
        val desired = if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != desired) {
            AppCompatDelegate.setDefaultNightMode(desired)
        }
        setTheme(if (intent.getStringExtra(EXTRA_PALETTE) == "ROSE") {
            R.style.Theme_TharMesh_Rose
        } else {
            R.style.Theme_TharMesh
        })
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_SCREEN)
        val layout = SCREENS[name]
        if (layout == null) {
            val message = TextView(this)
            message.text = "Unknown preview"
            setContentView(message)
            return
        }
        try {
            setContentView(layout)
        } catch (error: Throwable) {
            val message = TextView(this)
            message.text = "Preview failed"
            val root = FrameLayout(this)
            root.addView(message)
            setContentView(root)
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_PALETTE = "palette"
        const val EXTRA_NIGHT = "night"

        private val SCREENS = mapOf(
            "login" to R.layout.activity_login,
            "chat" to R.layout.activity_chat,
            "devices" to R.layout.fragment_devices,
            "dashboard" to R.layout.fragment_dashboard,
            "status" to R.layout.fragment_status,
            "settings" to R.layout.fragment_settings,
            "contacts" to R.layout.fragment_contacts,
            "onboarding" to R.layout.activity_onboarding,
            "profile" to R.layout.activity_profile,
            "about" to R.layout.activity_about,
            "account" to R.layout.activity_account_flow,
            "scanner" to R.layout.activity_scan_qr,
            "qr" to R.layout.activity_my_qr,
            "terms" to R.layout.activity_terms_gate,
            "unavailable" to R.layout.activity_unavailable
        )
    }
}

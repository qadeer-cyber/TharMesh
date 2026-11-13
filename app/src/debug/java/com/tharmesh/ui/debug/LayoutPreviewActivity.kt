// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.debug

import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.ui.settings.SettingsSections
import tharmesh.app.R

/**
 * Debug-only layout viewer. The screen name must be on the whitelist.
 * Sample rows are preview copy. This activity does not sign in or store a token.
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
            setContentView(TextView(this).apply { text = "Unknown preview" })
            return
        }
        try {
            setContentView(layout)
            seed(name)
        } catch (error: Throwable) {
            val message = TextView(this)
            message.text = "Preview failed"
            val root = FrameLayout(this)
            root.addView(message)
            setContentView(root)
        }
    }

    private fun seed(name: String?) {
        when (name) {
            "chat" -> seedChat()
            "settings" -> findViewById<LinearLayout>(R.id.settings_rows)?.let {
                SettingsSections.render(this, it)
            }
            "profile" -> findViewById<LinearLayout>(R.id.profile_settings_rows)?.let {
                SettingsSections.render(this, it)
            }
            "status" -> seedHealth()
            "contact" -> seedContact()
            "otp", "otp_code" -> seedOtp(name)
        }
    }

    private fun seedOtp(name: String?) {
        findViewById<TextView>(R.id.account_title)?.setText(R.string.account_otp_title)
        findViewById<TextView>(R.id.account_primary)?.setText(
            if (name == "otp_code") R.string.account_otp_verify else R.string.account_otp_send
        )
        findViewById<TextView>(R.id.account_body)?.setText(
            if (name == "otp_code") R.string.account_otp_verify_body else R.string.account_otp_request_body
        )
        findViewById<EditText>(R.id.account_field_1)?.apply {
            visibility = View.VISIBLE
            setText(if (name == "otp_code") "person@example.com" else "")
            isEnabled = name != "otp_code"
        }
        listOf(R.id.account_field_3, R.id.account_field_4).forEach { id ->
            findViewById<View>(id)?.visibility = View.GONE
        }
        findViewById<EditText>(R.id.account_field_2)?.apply {
            if (name == "otp_code") {
                visibility = View.VISIBLE
                hint = getString(R.string.account_otp_code)
                gravity = android.view.Gravity.CENTER
                textSize = 28f
                letterSpacing = 0.3f
            } else {
                visibility = View.GONE
            }
        }
        if (name == "otp_code") {
            findViewById<TextView>(R.id.account_secondary)?.apply {
                visibility = View.VISIBLE
                setText(R.string.account_otp_resend)
            }
            findViewById<TextView>(R.id.account_tertiary)?.apply {
                visibility = View.VISIBLE
                setText(R.string.account_otp_change)
            }
        }
    }

    private fun seedChat() {
        findViewById<TextView>(R.id.text_chat_title)?.text = "Amina"
        findViewById<TextView>(R.id.text_chat_avatar)?.text = "A"
        findViewById<TextView>(R.id.text_top_status)?.text = "Offline · not in range"
        findViewById<View>(R.id.button_send)?.isEnabled = true
        val recycler = findViewById<RecyclerView>(R.id.recycler_messages) ?: return
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SampleChatAdapter()
    }

    private fun seedHealth() {
        val battery = batteryPercent()
        bindStat(R.id.health_battery, R.drawable.ic_battery, battery, R.string.status_battery_label)
        bindStat(R.id.health_mesh, R.drawable.ic_wifi, "0 nodes", R.string.status_network_label)
        bindStat(R.id.health_storage, R.drawable.ic_storage, "\u2014", R.string.status_storage_label)
    }

    private fun batteryPercent(): String {
        val manager = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return "\u2014"
        val value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (value > 0) "$value%" else "\u2014"
    }

    private fun bindStat(id: Int, icon: Int, value: String, label: Int) {
        val row = findViewById<View>(id) ?: return
        row.findViewById<ImageView>(R.id.health_icon)?.setImageResource(icon)
        row.findViewById<TextView>(R.id.health_value)?.text = value
        row.findViewById<TextView>(R.id.health_label)?.setText(label)
    }

    private fun seedContact() {
        findViewById<TextView>(R.id.profile_name)?.text = "Amina"
        findViewById<TextView>(R.id.profile_status)?.text = "Nearby when the mesh is up"
        findViewById<TextView>(R.id.profile_userid)?.text = "user-amina"
        findViewById<TextView>(R.id.profile_avatar)?.text = "A"
    }

    private class SampleChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = listOf(
            Row.Day("Today"),
            Row.Mine("On my way through the bazaar.", "10:02", "\u2713"),
            Row.Mine("Sending the next one when a peer is in range.", "10:02", "\u23F3"),
            Row.Peer("I can read the first. The second is still queued."),
            Row.Mine("This one failed. Long-press to retry.", "10:04", "!")
        )

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Day -> 0
            is Row.Mine -> 1
            is Row.Peer -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                0 -> R.layout.item_msg_date
                1 -> R.layout.item_msg_me
                else -> R.layout.item_msg_peer
            }
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val row = rows[position]) {
                is Row.Day -> view.findViewById<TextView>(R.id.text_day).text = row.label
                is Row.Mine -> {
                    view.findViewById<TextView>(R.id.text_body).text = row.body
                    view.findViewById<TextView>(R.id.text_time).text = row.time
                    view.findViewById<TextView>(R.id.text_status).text = row.tick
                }
                is Row.Peer -> {
                    view.findViewById<TextView>(R.id.text_body).text = row.body
                    view.findViewById<TextView>(R.id.text_time).text = "10:03"
                }
            }
        }

        override fun getItemCount(): Int = rows.size

        private sealed class Row {
            data class Day(val label: String) : Row()
            data class Mine(val body: String, val time: String, val tick: String) : Row()
            data class Peer(val body: String) : Row()
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
            "otp" to R.layout.activity_account_flow,
            "otp_code" to R.layout.activity_account_flow,
            "scanner" to R.layout.activity_scan_qr,
            "qr" to R.layout.activity_my_qr,
            "terms" to R.layout.activity_terms_gate,
            "legal" to R.layout.activity_legal_doc,
            "blocked" to R.layout.activity_blocked_contacts,
            "diagnostics" to R.layout.activity_diagnostics,
            "contact" to R.layout.activity_contact_profile,
            "newchat" to R.layout.sheet_new_chat,
            "picker" to R.layout.sheet_device_picker,
            "dialog" to R.layout.ds_dialog,
            "unavailable" to R.layout.activity_unavailable
        )
    }
}

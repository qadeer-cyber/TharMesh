// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.
package com.tharmesh.ui.settings

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import tharmesh.app.R

/**
 * WhatsApp-style settings section list. Shared between [SettingsFragment] and
 * [com.tharmesh.ui.profile.ProfileActivity] so the two surfaces are guaranteed
 * to stay consistent.
 *
 * Each section opens [SettingsSectionActivity] with the section key as an
 * intent extra. The activity inflates the appropriate sub-layout. Invite is
 * handled inline via [Intent.ACTION_SEND] and never opens a section screen.
 */
object SettingsSections {

    /**
     * Stable section keys. Persisted nowhere except in cross-process intents,
     * but kept stable so that a deep-link from another part of the app (e.g.
     * the Disaster Mode banner long-press eventually pointing at the
     * Notifications section) keeps working across releases.
     */
    enum class Key {
        ACCOUNT,
        PRIVACY,
        LISTS,
        CHATS,
        NOTIFICATIONS,
        STORAGE,
        ACCESSIBILITY,
        LANGUAGE,
        HELP
    }

    fun render(context: Context, container: LinearLayout) {
        container.removeAllViews()
        addRow(
            context, container,
            Key.ACCOUNT, R.drawable.ic_lock,
            R.string.settings_section_account, R.string.settings_section_account_sub
        )
        addRow(
            context, container,
            Key.PRIVACY, R.drawable.ic_shield_outline,
            R.string.settings_section_privacy, R.string.settings_section_privacy_sub
        )
        addRow(
            context, container,
            Key.LISTS, R.drawable.ic_user,
            R.string.settings_section_lists, R.string.settings_section_lists_sub
        )
        addRow(
            context, container,
            Key.CHATS, R.drawable.ic_message,
            R.string.settings_section_chats, R.string.settings_section_chats_sub
        )
        addRow(
            context, container,
            Key.NOTIFICATIONS, R.drawable.ic_bell,
            R.string.settings_section_notifications, R.string.settings_section_notifications_sub
        )
        addRow(
            context, container,
            Key.STORAGE, R.drawable.ic_storage,
            R.string.settings_section_storage, R.string.settings_section_storage_sub
        )
        addRow(
            context, container,
            Key.ACCESSIBILITY, R.drawable.ic_user,
            R.string.settings_section_accessibility, R.string.settings_section_accessibility_sub
        )
        addRow(
            context, container,
            Key.LANGUAGE, R.drawable.ic_globe,
            R.string.settings_section_language, R.string.settings_section_language_sub
        )
        addRow(
            context, container,
            Key.HELP, R.drawable.ic_help,
            R.string.settings_section_help, R.string.settings_section_help_sub
        )
        addInviteRow(context, container)
    }

    private fun addRow(
        context: Context,
        container: LinearLayout,
        key: Key,
        @DrawableRes iconRes: Int,
        @StringRes titleRes: Int,
        @StringRes subtitleRes: Int
    ) {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_settings_section_row, container, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.row_title).setText(titleRes)
        row.findViewById<TextView>(R.id.row_subtitle).setText(subtitleRes)
        row.setOnClickListener {
            val intent = Intent(context, SettingsSectionActivity::class.java)
            intent.putExtra(SettingsSectionActivity.EXTRA_SECTION, key.name)
            context.startActivity(intent)
        }
        container.addView(row)
    }

    private fun addInviteRow(context: Context, container: LinearLayout) {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_settings_section_row, container, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(R.drawable.ic_user)
        row.findViewById<TextView>(R.id.row_title).setText(R.string.settings_section_invite)
        // No subtitle on the invite row to mirror the WhatsApp screenshot.
        row.findViewById<TextView>(R.id.row_subtitle).visibility = View.GONE
        row.setOnClickListener { fireInviteIntent(context) }
        container.addView(row)
    }

    /**
     * Share the locally-installed APK so a peer can sideload TharMesh without
     * any backend or app-store. Falls back to a plain text invite if the APK
     * file isn't reachable (e.g. on devices that don't expose the app's own
     * APK path through `applicationInfo.publicSourceDir`).
     */
    private fun fireInviteIntent(context: Context) {
        val text = context.getString(R.string.settings_invite_message)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            context.getString(R.string.settings_section_invite)
        )
        context.startActivity(chooser)
    }
}

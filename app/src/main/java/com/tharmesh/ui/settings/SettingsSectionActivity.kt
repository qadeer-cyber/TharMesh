// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.
package com.tharmesh.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.TharMeshApp
import com.tharmesh.auth.GoogleAuthService
import com.tharmesh.data.UserPrefs
import com.tharmesh.disaster.DisasterModeController
import com.tharmesh.ui.auth.LoginActivity
import com.tharmesh.ui.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.R
import java.io.File

/**
 * Generic host activity for a single WhatsApp-style settings section. The
 * section to render is supplied via [EXTRA_SECTION] (the [SettingsSections.Key]
 * enum name). All section content is built programmatically into the
 * [activity_settings_section] layout's [section_content] container so we don't
 * need 9 distinct activities.
 *
 * Sections deliberately mix real-data rows (identity, message count, theme,
 * disaster mode) and clearly-labelled placeholder rows (disappearing messages,
 * wallpaper) per the PR scope. No fake data anywhere — placeholder rows are
 * tagged with a "Coming soon" subtitle so the UI is honest about what works.
 */
class SettingsSectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECTION = "section"
    }

    private lateinit var titleView: TextView
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyFromPrefs(this)
        setContentView(R.layout.activity_settings_section)
        titleView = findViewById(R.id.section_title)
        content = findViewById(R.id.section_content)
        findViewById<ImageView>(R.id.section_back).setOnClickListener { finish() }

        val keyName = intent.getStringExtra(EXTRA_SECTION)
        val key = runCatching { SettingsSections.Key.valueOf(keyName ?: "") }.getOrNull()
        if (key == null) {
            finish()
            return
        }
        renderSection(key)
    }

    override fun onResume() {
        super.onResume()
        // Re-render so toggles reflect any state mutated outside this screen
        // (e.g. Disaster Mode flipped from the chats top banner).
        val keyName = intent.getStringExtra(EXTRA_SECTION) ?: return
        val key = runCatching { SettingsSections.Key.valueOf(keyName) }.getOrNull() ?: return
        renderSection(key)
    }

    private fun renderSection(key: SettingsSections.Key) {
        content.removeAllViews()
        when (key) {
            SettingsSections.Key.ACCOUNT -> renderAccount()
            SettingsSections.Key.PRIVACY -> renderPrivacy()
            SettingsSections.Key.LISTS -> renderLists()
            SettingsSections.Key.CHATS -> renderChats()
            SettingsSections.Key.NOTIFICATIONS -> renderNotifications()
            SettingsSections.Key.STORAGE -> renderStorage()
            SettingsSections.Key.ACCESSIBILITY -> renderAccessibility()
            SettingsSections.Key.LANGUAGE -> renderLanguage()
            SettingsSections.Key.HELP -> renderHelp()
            SettingsSections.Key.LEGAL -> renderLegal()
            // Stage 10.1 — ABOUT is normally launched directly from the
            // settings list (see [SettingsSections.addAboutRow]), so this
            // branch is only hit if a stale deep-link arrives. Forward
            // to the dedicated activity and finish this host.
            SettingsSections.Key.ABOUT -> {
                startActivity(
                    android.content.Intent(this, com.tharmesh.ui.about.AboutActivity::class.java)
                )
                finish()
            }
        }
    }

    // --- Sections ---------------------------------------------------------

    private fun renderAccount() {
        titleView.setText(R.string.settings_section_account)
        val profile = UserPrefs.readProfile(this)
        val identity = UserPrefs.readIdentity(this)

        addInfoRow(
            R.drawable.ic_user,
            getString(R.string.settings_account_user_id),
            profile?.userId ?: "—"
        )
        addInfoRow(
            R.drawable.ic_shield,
            getString(R.string.settings_account_fingerprint),
            identity?.fingerprint?.let { formatFingerprint(it) }
                ?: getString(R.string.settings_account_fingerprint_missing)
        )
        addInfoRow(
            R.drawable.ic_lock,
            getString(R.string.settings_account_provider),
            (profile?.authProvider ?: UserPrefs.PROVIDER_ANONYMOUS).replaceFirstChar { it.uppercase() }
        )
        addClickRow(
            R.drawable.ic_alert,
            getString(R.string.settings_account_sign_out),
            getString(R.string.settings_account_sign_out_sub)
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_account_sign_out)
                .setMessage(R.string.settings_account_sign_out_confirm)
                .setPositiveButton(R.string.settings_account_sign_out) { _, _ ->
                    GoogleAuthService(this).signOut()
                    TharMeshApp.get().stopMesh()
                    UserPrefs.signOut(this)
                    // Stage 8.4 \u2014 sign-out wipes the prefs file (which
                    // includes the persisted block list) but the
                    // BlockedContacts singleton would otherwise carry the
                    // previous account's in-memory cache into the next
                    // sign-in. Invalidate it so the new account starts clean.
                    com.tharmesh.data.BlockedContacts.onSignOut()
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun renderPrivacy() {
        titleView.setText(R.string.settings_section_privacy)
        // Stage 8.4 — Pakistan compliance: replace the placeholder info
        // row with a real Blocked-contacts entry. The subtitle reflects
        // the current count so a glance at Settings tells the user
        // whether they have an active block list.
        val blockedCount = com.tharmesh.data.BlockedContacts.snapshot(this).size
        val blockedSubtitle = if (blockedCount == 0) {
            getString(R.string.settings_privacy_blocked_sub_placeholder)
        } else {
            getString(R.string.settings_blocked_contacts_count_fmt, blockedCount)
        }
        addClickRow(
            R.drawable.ic_shield_outline,
            getString(R.string.settings_privacy_blocked),
            blockedSubtitle
        ) {
            startActivity(
                Intent(this, com.tharmesh.ui.legal.BlockedContactsActivity::class.java)
            )
        }
        addInfoRow(
            R.drawable.ic_lock,
            getString(R.string.settings_privacy_disappearing),
            getString(R.string.settings_coming_soon)
        )
    }

    /**
     * Stage 8.4 \u2014 Pakistan legal defensibility hardening. Dedicated
     * Settings \u2192 Legal section. Every row opens [LegalDocActivity]
     * with the matching [com.tharmesh.ui.legal.LegalDocBodies] key, so
     * the body text is a single source of truth shared with the
     * first-launch acceptance gate.
     */
    private fun renderLegal() {
        titleView.setText(R.string.settings_section_legal)
        addClickRow(
            R.drawable.ic_lock,
            getString(R.string.settings_legal_terms),
            getString(R.string.settings_legal_terms_sub)
        ) {
            startActivity(
                com.tharmesh.ui.legal.LegalDocActivity.intent(
                    this, com.tharmesh.ui.legal.LegalDocBodies.TERMS
                )
            )
        }
        addClickRow(
            R.drawable.ic_shield_outline,
            getString(R.string.settings_legal_privacy),
            getString(R.string.settings_legal_privacy_sub)
        ) {
            startActivity(
                com.tharmesh.ui.legal.LegalDocActivity.intent(
                    this, com.tharmesh.ui.legal.LegalDocBodies.PRIVACY
                )
            )
        }
        addClickRow(
            R.drawable.ic_help,
            getString(R.string.settings_legal_disclaimer),
            getString(R.string.settings_legal_disclaimer_sub)
        ) {
            startActivity(
                com.tharmesh.ui.legal.LegalDocActivity.intent(
                    this, com.tharmesh.ui.legal.LegalDocBodies.DISCLAIMER
                )
            )
        }
    }

    private fun renderLists() {
        titleView.setText(R.string.settings_section_lists)
        addInfoRow(
            R.drawable.ic_user,
            getString(R.string.settings_section_lists),
            getString(R.string.settings_coming_soon)
        )
    }

    private fun renderChats() {
        titleView.setText(R.string.settings_section_chats)
        val mode = UserPrefs.getThemeMode(this)
        addClickRow(
            R.drawable.ic_moon,
            getString(R.string.settings_chats_theme),
            getString(modeLabel(mode))
        ) { showThemePicker() }
        addInfoRow(
            R.drawable.ic_message,
            getString(R.string.settings_chats_wallpaper),
            getString(R.string.settings_coming_soon)
        )
        addInfoRow(
            R.drawable.ic_storage,
            getString(R.string.settings_chats_history),
            getString(R.string.settings_coming_soon)
        )
    }

    private fun renderNotifications() {
        titleView.setText(R.string.settings_section_notifications)
        addSwitchRow(
            R.drawable.ic_bell,
            getString(R.string.settings_notif_sound),
            null,
            UserPrefs.isNotificationSoundEnabled(this)
        ) { checked -> UserPrefs.setNotificationSoundEnabled(this, checked) }
        addSwitchRow(
            R.drawable.ic_bell,
            getString(R.string.settings_notif_vibrate),
            null,
            UserPrefs.isNotificationVibrateEnabled(this)
        ) { checked -> UserPrefs.setNotificationVibrateEnabled(this, checked) }

        // Disaster Mode is a vibrate + ring feature so it lives under
        // Notifications but is rendered as a click-row that opens the same
        // confirm-then-flip dialog as Stage 6.3's settings entry.
        val disasterOn = UserPrefs.isDisasterModeEnabled(this)
        addClickRow(
            R.drawable.ic_alert,
            getString(R.string.settings_disaster),
            if (disasterOn) getString(R.string.settings_disaster_sub_on)
            else getString(R.string.settings_disaster_sub_off)
        ) { showDisasterDialog() }
    }

    private fun renderStorage() {
        titleView.setText(R.string.settings_section_storage)
        addInfoRow(
            R.drawable.ic_storage,
            getString(R.string.settings_storage_messages),
            getString(R.string.settings_storage_loading)
        )
        addInfoRow(
            R.drawable.ic_storage,
            getString(R.string.settings_storage_cache),
            humanReadableBytes(approximateCacheSize(this))
        )
        // Async query the message count off the main thread so we don't block
        // section render on Room.
        CoroutineScope(Dispatchers.Main).launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val db = TharMeshApp.get().database
                    db.messageDao().count()
                }.getOrDefault(0)
            }
            // Replace the first row's value text in-place.
            val row = content.getChildAt(0) ?: return@launch
            row.findViewById<TextView>(R.id.row_subtitle)?.text =
                resources.getQuantityString(R.plurals.settings_storage_messages_count, count, count)
        }
    }

    private fun renderAccessibility() {
        titleView.setText(R.string.settings_section_accessibility)
        val highContrast = UserPrefs.getThemeMode(this) == ThemeManager.Mode.DARK
        addSwitchRow(
            R.drawable.ic_user,
            getString(R.string.settings_accessibility_contrast),
            getString(R.string.settings_accessibility_contrast_sub),
            highContrast
        ) { checked ->
            val target = if (checked) ThemeManager.Mode.DARK else ThemeManager.Mode.SYSTEM
            UserPrefs.setThemeMode(this, target)
            ThemeManager.apply(target)
            recreate()
        }
    }

    private fun renderLanguage() {
        titleView.setText(R.string.settings_section_language)
        val current = resources.configuration.locale.displayName
        addInfoRow(
            R.drawable.ic_globe,
            getString(R.string.settings_section_language),
            getString(R.string.settings_language_current, current)
        )
        addInfoRow(
            R.drawable.ic_globe,
            getString(R.string.settings_language_more),
            getString(R.string.settings_coming_soon)
        )
    }

    private fun renderHelp() {
        titleView.setText(R.string.settings_section_help)
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "—"
        addInfoRow(
            R.drawable.ic_help,
            getString(R.string.settings_help_version),
            version
        )
        // Diagnostics entry — preserves the Stage 5.1 access path that used
        // to live on the legacy Settings screen. Tapping it opens the same
        // [DiagnosticsActivity] used by Field Test Mode.
        addClickRow(
            R.drawable.ic_storage,
            getString(R.string.settings_diagnostics),
            getString(R.string.settings_diagnostics_sub)
        ) {
            startActivity(
                Intent(this, com.tharmesh.ui.diagnostics.DiagnosticsActivity::class.java)
            )
        }
        addClickRow(
            R.drawable.ic_help,
            getString(R.string.settings_help_contact),
            getString(R.string.settings_help_contact_sub)
        ) {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    getString(R.string.settings_help_contact_subject)
                )
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    R.string.settings_help_no_mail_client,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // --- Row builders -----------------------------------------------------

    private fun addInfoRow(iconRes: Int, title: String, subtitle: String?) {
        addClickRow(iconRes, title, subtitle, null)
    }

    private fun addClickRow(
        iconRes: Int,
        title: String,
        subtitle: String?,
        onClick: (() -> Unit)?
    ) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_section_row, content, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.row_title).text = title
        val sub = row.findViewById<TextView>(R.id.row_subtitle)
        if (subtitle.isNullOrBlank()) sub.visibility = View.GONE else sub.text = subtitle
        if (onClick != null) row.setOnClickListener { onClick() }
        else row.isClickable = false
        content.addView(row)
    }

    private fun addSwitchRow(
        iconRes: Int,
        title: String,
        subtitle: String?,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_section_row, content, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.row_title).text = title
        val sub = row.findViewById<TextView>(R.id.row_subtitle)
        if (subtitle.isNullOrBlank()) sub.visibility = View.GONE else sub.text = subtitle
        val sw = row.findViewById<Switch>(R.id.row_switch)
        sw.visibility = View.VISIBLE
        sw.isChecked = initial
        row.setOnClickListener {
            sw.isChecked = !sw.isChecked
            onChange(sw.isChecked)
        }
        content.addView(row)
    }

    // --- Helpers ----------------------------------------------------------

    private fun showThemePicker() {
        val ctx = this
        val labels = arrayOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val current = UserPrefs.getThemeMode(ctx)
        val checked = when (current) {
            ThemeManager.Mode.SYSTEM -> 0
            ThemeManager.Mode.LIGHT -> 1
            ThemeManager.Mode.DARK -> 2
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_chats_theme)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val target = when (which) {
                    0 -> ThemeManager.Mode.SYSTEM
                    1 -> ThemeManager.Mode.LIGHT
                    else -> ThemeManager.Mode.DARK
                }
                UserPrefs.setThemeMode(ctx, target)
                ThemeManager.apply(target)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showDisasterDialog() {
        val ctx = this
        val isOn = UserPrefs.isDisasterModeEnabled(ctx)
        val titleRes = if (isOn) R.string.settings_disaster_dialog_disable_title
        else R.string.settings_disaster_dialog_title
        val msgRes = if (isOn) R.string.settings_disaster_dialog_disable_body
        else R.string.settings_disaster_dialog_body
        val confirmRes = if (isOn) R.string.settings_disaster_dialog_disable
        else R.string.settings_disaster_dialog_enable
        AlertDialog.Builder(ctx)
            .setTitle(titleRes)
            .setMessage(msgRes)
            .setPositiveButton(confirmRes) { _, _ ->
                DisasterModeController.setEnabled(ctx, !isOn)
                renderSection(SettingsSections.Key.NOTIFICATIONS)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun modeLabel(mode: ThemeManager.Mode): Int = when (mode) {
        ThemeManager.Mode.SYSTEM -> R.string.settings_theme_system
        ThemeManager.Mode.LIGHT -> R.string.settings_theme_light
        ThemeManager.Mode.DARK -> R.string.settings_theme_dark
    }

    private fun formatFingerprint(fp: String): String {
        // Group every 4 chars for readability; matches the existing chat-header
        // shield rendering convention.
        return fp.chunked(4).joinToString(" ")
    }

    private fun approximateCacheSize(ctx: Context): Long {
        val cacheDir: File = ctx.cacheDir
        return walkSize(cacheDir)
    }

    private fun walkSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            total += if (child.isDirectory) walkSize(child) else child.length()
        }
        return total
    }

    private fun humanReadableBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var b = bytes.toDouble() / 1024.0
        var unit = 0
        while (b >= 1024.0 && unit < units.lastIndex) {
            b /= 1024.0
            unit++
        }
        return String.format("%.1f %s", b, units[unit])
    }
}

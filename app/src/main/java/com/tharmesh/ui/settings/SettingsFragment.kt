package com.tharmesh.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.tharmesh.data.UserPrefs
import com.tharmesh.ui.auth.LoginActivity
import tharmesh.app.R

/** Settings tab: profile summary + options list. Rows are demo-only for now. */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val profile = UserPrefs.readProfile(ctx)
        val avatar = view.findViewById<TextView>(R.id.settings_avatar)
        val username = view.findViewById<TextView>(R.id.settings_username)
        val userId = view.findViewById<TextView>(R.id.settings_userid)
        val displayName = profile?.username ?: "Anonymous"
        username.text = displayName
        userId.text = profile?.userId ?: "user-offline"
        avatar.text = displayName.take(1).uppercase()

        val rows = view.findViewById<LinearLayout>(R.id.settings_rows)
        rows.removeAllViews()
        addRow(rows, R.drawable.ic_user, R.drawable.bg_round_icon_cyan,
            R.string.settings_profile, R.string.settings_profile_sub)
        addRow(rows, R.drawable.ic_lock, R.drawable.bg_round_icon_green,
            R.string.settings_security, R.string.settings_security_sub)
        addRow(rows, R.drawable.ic_wifi, R.drawable.bg_round_icon_cyan,
            R.string.settings_mesh, R.string.settings_mesh_sub)
        addRow(rows, R.drawable.ic_storage, R.drawable.bg_round_icon_amber,
            R.string.settings_storage, R.string.settings_storage_sub)
        addRow(rows, R.drawable.ic_bolt, R.drawable.bg_round_icon_amber,
            R.string.settings_battery, R.string.settings_battery_sub)
        addRow(rows, R.drawable.ic_shield, R.drawable.bg_round_icon_green,
            R.string.settings_about, R.string.settings_about_sub)

        view.findViewById<Button>(R.id.button_sign_out).setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_sign_out)
                .setMessage("Sign out and clear your local profile?")
                .setPositiveButton(R.string.settings_sign_out) { _, _ ->
                    UserPrefs.signOut(ctx)
                    val intent = Intent(ctx, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    requireActivity().finish()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun addRow(
        parent: LinearLayout,
        iconRes: Int,
        iconBgRes: Int,
        titleRes: Int,
        subtitleRes: Int
    ) {
        val row = LayoutInflater.from(parent.context).inflate(R.layout.item_settings_row, parent, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<View>(R.id.row_icon_bg).setBackgroundResource(iconBgRes)
        row.findViewById<TextView>(R.id.row_title).setText(titleRes)
        row.findViewById<TextView>(R.id.row_subtitle).setText(subtitleRes)
        parent.addView(row)
    }
}

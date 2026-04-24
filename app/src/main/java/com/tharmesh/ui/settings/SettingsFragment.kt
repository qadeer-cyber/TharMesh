package com.tharmesh.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.tharmesh.TharMeshApp
import com.tharmesh.auth.GoogleAuthService
import com.tharmesh.data.UserPrefs
import com.tharmesh.ui.auth.LoginActivity
import tharmesh.app.R

/**
 * Settings tab: profile summary + options list.
 *
 * Tapping the Profile row opens an edit dialog that updates the local display name
 * (persisted via [UserPrefs]). Other rows are still demo-only for now.
 */
class SettingsFragment : Fragment() {

    private lateinit var avatar: TextView
    private lateinit var username: TextView
    private lateinit var userIdView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        avatar = view.findViewById(R.id.settings_avatar)
        username = view.findViewById(R.id.settings_username)
        userIdView = view.findViewById(R.id.settings_userid)
        renderProfile()

        val rows = view.findViewById<LinearLayout>(R.id.settings_rows)
        rows.removeAllViews()
        val profileRow = addRow(rows, R.drawable.ic_user, R.drawable.bg_round_icon_cyan,
            R.string.settings_profile, R.string.settings_profile_sub)
        profileRow.setOnClickListener { showEditProfileDialog() }
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
                    // Revoke Google session first so LoginActivity won't auto-re-sign-in via the
                    // cached GoogleSignInAccount, then tear down the mesh so a different user
                    // logging in afterwards gets a fresh MeshEngine bound to their userId.
                    GoogleAuthService(ctx).signOut()
                    TharMeshApp.get().stopMesh()
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

    private fun renderProfile() {
        val ctx = requireContext()
        val profile = UserPrefs.readProfile(ctx)
        val displayName = profile?.username ?: "Anonymous"
        username.text = displayName
        userIdView.text = profile?.userId ?: "user-offline"
        avatar.text = displayName.take(1).uppercase()
    }

    private fun showEditProfileDialog() {
        val ctx = requireContext()
        val current = UserPrefs.readProfile(ctx) ?: return
        val input = EditText(ctx).apply {
            setText(current.username)
            setSelection(current.username.length)
            hint = getString(R.string.profile_edit_hint)
        }
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.profile_edit_title)
            .setView(wrapper)
            .setPositiveButton(R.string.profile_edit_save) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newName != current.username) {
                    UserPrefs.saveProfile(ctx, current.copy(username = newName))
                    renderProfile()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun addRow(
        parent: LinearLayout,
        iconRes: Int,
        iconBgRes: Int,
        titleRes: Int,
        subtitleRes: Int
    ): View {
        val row = LayoutInflater.from(parent.context).inflate(R.layout.item_settings_row, parent, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<View>(R.id.row_icon_bg).setBackgroundResource(iconBgRes)
        row.findViewById<TextView>(R.id.row_title).setText(titleRes)
        row.findViewById<TextView>(R.id.row_subtitle).setText(subtitleRes)
        parent.addView(row)
        return row
    }
}

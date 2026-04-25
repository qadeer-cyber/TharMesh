// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.
package com.tharmesh.ui.profile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.ui.contacts.MyQrActivity
import com.tharmesh.ui.settings.SettingsSections
import com.tharmesh.ui.theme.ThemeManager
import tharmesh.app.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * WhatsApp-style Profile + Settings landing screen. Reached by tapping the
 * top-right avatar on the chats home. Renders an editable header (avatar,
 * display name, status / "What's happening?") and the same WhatsApp-style
 * section list as [com.tharmesh.ui.settings.SettingsFragment].
 *
 * Avatars: the system picker ([Intent.ACTION_GET_CONTENT]) returns a
 * content:// URI that may not survive process death. We immediately copy
 * the chosen image into the app's private files dir as `profile_avatar.jpg`
 * so subsequent loads don't depend on the originating provider's permission
 * grant.
 *
 * Status + display-name edits are plain text dialogs that persist via
 * [UserPrefs]. No network round-trip; values stay device-local.
 */
class ProfileActivity : AppCompatActivity() {

    companion object {
        private const val PICK_IMAGE = 1001
        private const val AVATAR_FILE = "profile_avatar.jpg"
        private const val AVATAR_MAX_DIM = 512
    }

    private lateinit var avatarLetter: TextView
    private lateinit var avatarImage: ImageView
    private lateinit var displayName: TextView
    private lateinit var userIdView: TextView
    private lateinit var statusBubble: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyFromPrefs(this)
        setContentView(R.layout.activity_profile)

        avatarLetter = findViewById(R.id.profile_avatar_letter)
        avatarImage = findViewById(R.id.profile_avatar_image)
        displayName = findViewById(R.id.profile_display_name)
        userIdView = findViewById(R.id.profile_user_id)
        statusBubble = findViewById(R.id.profile_status_bubble)

        findViewById<ImageView>(R.id.profile_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.profile_qr).setOnClickListener {
            startActivity(Intent(this, MyQrActivity::class.java))
        }
        findViewById<ImageView>(R.id.profile_search).setOnClickListener {
            // Search currently jumps back to chats — the search pill on the
            // chats home is the canonical search surface. Honest, no fake
            // implementation.
            finish()
        }

        findViewById<View>(R.id.profile_edit_name).setOnClickListener { showEditNameDialog() }
        findViewById<View>(R.id.profile_change_photo).setOnClickListener { launchPhotoPicker() }
        findViewById<View>(R.id.profile_avatar_frame).setOnClickListener { launchPhotoPicker() }
        statusBubble.setOnClickListener { showEditStatusDialog() }

        renderHeader()

        val rows = findViewById<LinearLayout>(R.id.profile_settings_rows)
        SettingsSections.render(this, rows)
    }

    override fun onResume() {
        super.onResume()
        renderHeader()
        // Re-render the section list as well in case a sub-screen changed
        // anything that the rows display (currently subtitles are static, but
        // this keeps us future-proof for e.g. a Disaster Mode subtitle).
        val rows = findViewById<LinearLayout>(R.id.profile_settings_rows)
        SettingsSections.render(this, rows)
    }

    private fun renderHeader() {
        val profile: UserProfile? = UserPrefs.readProfile(this)
        val name = profile?.username?.takeIf { it.isNotBlank() } ?: getString(R.string.profile_anonymous)
        displayName.text = name
        userIdView.text = profile?.userId ?: "—"
        avatarLetter.text = name.take(1).uppercase()

        val status = UserPrefs.getStatus(this)
        if (status.isBlank()) {
            statusBubble.setText(R.string.profile_status_placeholder)
        } else {
            statusBubble.text = status
        }

        val avatarPath = UserPrefs.getAvatarLocalPath(this)
        if (avatarPath != null) {
            val file = File(avatarPath)
            val bmp = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            if (bmp != null) {
                avatarImage.setImageBitmap(toCircularBitmap(bmp))
                avatarImage.visibility = View.VISIBLE
                avatarLetter.visibility = View.INVISIBLE
                return
            }
        }
        avatarImage.visibility = View.GONE
        avatarLetter.visibility = View.VISIBLE
    }

    private fun showEditNameDialog() {
        val ctx = this
        val profile = UserPrefs.readProfile(ctx)
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(profile?.username ?: "")
            setSelection(text.length)
            hint = getString(R.string.profile_name_hint)
        }
        val container = FrameLayout(ctx).apply {
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.profile_edit_name_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    val current = UserPrefs.readProfile(ctx) ?: UserPrefs.ensureProfile(ctx)
                    UserPrefs.saveProfile(ctx, current.copy(username = newName))
                    renderHeader()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showEditStatusDialog() {
        val ctx = this
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(UserPrefs.getStatus(ctx))
            setSelection(text.length)
            hint = getString(R.string.profile_status_hint)
        }
        val container = FrameLayout(ctx).apply {
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.profile_edit_status_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                UserPrefs.setStatus(ctx, input.text.toString().trim())
                renderHeader()
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                UserPrefs.setStatus(ctx, "")
                renderHeader()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun launchPhotoPicker() {
        // ACTION_GET_CONTENT works back to the project's minSdk and doesn't
        // require any runtime permissions — the OS owns the picker, we
        // receive a one-shot URI we read once and copy to internal storage.
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.profile_change_photo_cd))
        try {
            startActivityForResult(chooser, PICK_IMAGE)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.profile_no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val saved = persistAvatar(uri)
        if (saved != null) {
            UserPrefs.setAvatarLocalPath(this, saved.absolutePath)
            renderHeader()
        } else {
            Toast.makeText(this, R.string.profile_photo_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistAvatar(uri: Uri): File? {
        val target = File(filesDir, AVATAR_FILE)
        return runCatching {
            val input: InputStream = contentResolver.openInputStream(uri)
                ?: return@runCatching null
            val raw = input.use { BitmapFactory.decodeStream(it) }
                ?: return@runCatching null
            val scaled = scaleDown(raw, AVATAR_MAX_DIM)
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            target
        }.getOrNull()
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = w.toFloat() / h.toFloat()
        val (nw, nh) = if (w >= h) maxDim to (maxDim / ratio).toInt()
        else (maxDim * ratio).toInt() to maxDim
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /**
     * Crop [src] to a centred square then mask it into a circle. Avoids
     * pulling in any image-loading dependency — we only need this for the
     * one profile avatar.
     */
    private fun toCircularBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val xOff = (src.width - size) / 2
        val yOff = (src.height - size) / 2
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = -0x1
        }
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, Rect(xOff, yOff, xOff + size, yOff + size), rect, paint)
        return output
    }
}

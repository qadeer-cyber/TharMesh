// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.
package com.tharmesh.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tharmesh.data.UserPrefs
import com.tharmesh.ui.profile.ProfileActivity
import tharmesh.app.R
import java.io.File

/**
 * WhatsApp-style settings tab.
 *
 * Layout:
 *  - Profile preview row (avatar + display name + status). Taps open
 *    [ProfileActivity] for the full editable header.
 *  - Flat section list rendered by [SettingsSections] — every section
 *    routes through [SettingsSectionActivity], so the bottom-nav tab and
 *    the chats top-bar avatar share the exact same downstream surfaces.
 */
class SettingsFragment : Fragment() {

    private lateinit var avatarLetter: TextView
    private lateinit var avatarImage: ImageView
    private lateinit var username: TextView
    private lateinit var status: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        avatarLetter = view.findViewById(R.id.settings_avatar)
        avatarImage = view.findViewById(R.id.settings_avatar_image)
        username = view.findViewById(R.id.settings_username)
        status = view.findViewById(R.id.settings_status)

        view.findViewById<View>(R.id.settings_profile_row).setOnClickListener {
            startActivity(Intent(ctx, ProfileActivity::class.java))
        }

        renderProfilePreview()
        SettingsSections.render(ctx, view.findViewById(R.id.settings_rows))
    }

    override fun onResume() {
        super.onResume()
        renderProfilePreview()
    }

    private fun renderProfilePreview() {
        val ctx = context ?: return
        val profile = UserPrefs.readProfile(ctx)
        val name = profile?.username?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_anonymous)
        username.text = name
        avatarLetter.text = name.take(1).uppercase()

        val s = UserPrefs.getStatus(ctx)
        status.text = if (s.isBlank()) getString(R.string.profile_status_placeholder) else s

        val avatarPath = UserPrefs.getAvatarLocalPath(ctx)
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

    private fun toCircularBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val xOff = (src.width - size) / 2
        val yOff = (src.height - size) / 2
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1 }
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, Rect(xOff, yOff, xOff + size, yOff + size), rect, paint)
        return output
    }
}

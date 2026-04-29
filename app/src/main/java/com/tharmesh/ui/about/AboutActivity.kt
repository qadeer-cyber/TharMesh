// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.
package com.tharmesh.ui.about

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.ui.widget.applyPremiumPopTransition
import com.tharmesh.ui.widget.applyPremiumPress
import tharmesh.app.BuildConfig
import tharmesh.app.R

/**
 * Stage 10.1 — About / Founder Signature screen.
 *
 * Single-purpose activity rendered when the user taps Settings → About TharMesh.
 * Layout is fully defined in [R.layout.activity_about]; this class only:
 *
 *  1. Wires the back arrow + system back to [finish].
 *  2. Populates the system-info card from [BuildConfig] (version name, build
 *     type, short commit hash). Commit hash is the same value injected at
 *     build-time by `app/build.gradle` from `git rev-parse --short HEAD`,
 *     with a `"—"` fallback when git is unavailable.
 *  3. Wires the three external link rows (GitHub / Telegram / WhatsApp) to
 *     [Intent.ACTION_VIEW] launches with a friendly toast fallback when no
 *     app on the device can handle the URL.
 *  4. Drives a slow breathing animation on the hero icon halo. The animator
 *     is held in a field and cancelled in [onDestroy] (lessons from PR #46
 *     review — never start an infinite [ObjectAnimator] without holding a
 *     reference for cancellation).
 *
 * No business logic, no protocol/DB changes. Pure UI.
 */
class AboutActivity : AppCompatActivity() {

    /** Held so [onDestroy] can cancel the infinite breathing loop. */
    private var heroHaloAnim: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<ImageView>(R.id.about_back).setOnClickListener { finish() }

        populateSystemInfo()
        wireLinks()
        startHeroHaloAnimation()
    }

    override fun finish() {
        super.finish()
        applyPremiumPopTransition()
    }

    override fun onDestroy() {
        // PR #46 lesson: never leave an INFINITE animator running across
        // activity destruction. Cancel + null out so Choreographer drops
        // the strong reference chain.
        heroHaloAnim?.cancel()
        heroHaloAnim = null
        super.onDestroy()
    }

    private fun populateSystemInfo() {
        val display = AboutBuildInfo.format(
            version = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            commit = BuildConfig.GIT_COMMIT_HASH,
        )
        findViewById<TextView>(R.id.about_system_version_value).text = display.version
        findViewById<TextView>(R.id.about_system_buildtype_value).text = display.buildType
        findViewById<TextView>(R.id.about_system_commit_value).text = display.commit
    }

    private fun wireLinks() {
        bindLinkRow(R.id.about_link_github, R.string.about_link_url_github)
        bindLinkRow(R.id.about_link_telegram, R.string.about_link_url_telegram)
        bindLinkRow(R.id.about_link_whatsapp, R.string.about_link_url_whatsapp)
    }

    private fun bindLinkRow(rowId: Int, urlRes: Int) {
        val row = findViewById<LinearLayout>(rowId)
        row.applyPremiumPress()
        row.setOnClickListener {
            val url = getString(urlRes)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                showNoHandler()
            } catch (_: SecurityException) {
                // A few OEM ROMs throw SecurityException on ACTION_VIEW
                // when no default browser is set. Same user-visible
                // outcome — no app to open the link.
                showNoHandler()
            }
        }
    }

    private fun showNoHandler() {
        Toast.makeText(this, R.string.about_link_no_handler, Toast.LENGTH_SHORT).show()
    }

    /**
     * Slow, low-amplitude breathing on the hero halo. Property-only
     * (alpha + scale), respects the system animator-duration scale, and
     * is the only infinite loop on this screen so it doesn't compete with
     * any other Stage 9.2 perception animations (mesh graph / signal bars
     * are on different screens).
     */
    private fun startHeroHaloAnimation() {
        val halo: View = findViewById(R.id.hero_icon_halo)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 1f, 0.85f)
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f, 1f)
        heroHaloAnim = ObjectAnimator.ofPropertyValuesHolder(halo, alpha, scaleX, scaleY).apply {
            duration = 2400L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            start()
        }
    }
}

// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import tharmesh.app.R

/**
 * Stage 9.2 — soft halo behind an avatar that shows up only when the contact
 * is "online" (present in the live mesh peer set). Keeps the chat list /
 * contacts list visually quiet by default, then surfaces presence with a
 * subtle green glow ring around online contacts.
 *
 * Implementation is intentionally minimal: this is a [FrameLayout] that
 * toggles its own background between [R.drawable.bg_avatar_glow_online]
 * (a radial-gradient drawable) and a transparent `null` background. No
 * animator — the halo is static when present so the chat list doesn't
 * become a forest of pulsing dots. Motion is reserved for [PulsingDot] in
 * the brand header / per-row connection indicator.
 */
class GlowingAvatarFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var glowing: Boolean = false

    init {
        // Halo lives in [setBackgroundResource] so any avatar children
        // (ImageView / verification badge) render on top of it without
        // needing extra layers.
        setBackgroundResource(0)
    }

    /**
     * Toggle the halo. Idempotent — early-returns when the new state matches
     * the current one. Called from RecyclerView adapters bind() with the
     * online-state derived from the directory snapshot.
     */
    fun setOnline(online: Boolean) {
        if (online == glowing) return
        glowing = online
        if (online) {
            setBackgroundResource(R.drawable.bg_avatar_glow_online)
        } else {
            setBackgroundResource(0)
        }
    }

    /** Visible-for-testing — current halo state. */
    internal fun isGlowing(): Boolean = glowing
}

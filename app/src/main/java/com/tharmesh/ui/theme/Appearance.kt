// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.theme

import android.app.Activity
import com.tharmesh.data.UserPrefs
import tharmesh.app.R

/**
 * Ocean is the default. Rose replaces every accent; it does not mix blue in.
 * Call [applyTheme] before super.onCreate so the window is inflated in the
 * chosen palette. Light and dark still come from [ThemeManager].
 */
object Appearance {
    enum class Palette { OCEAN, ROSE }

    fun applyTheme(activity: Activity) {
        val palette = UserPrefs.getPalette(activity)
        activity.setTheme(
            if (palette == Palette.ROSE) R.style.Theme_TharMesh_Rose else R.style.Theme_TharMesh
        )
    }
}

/**
 * WCAG contrast for the palette hex values shipped in colors.xml.
 * Large text is bold control labels (14pt+ bold). Body text uses 4.5:1.
 */
object PaletteContrast {
    data class Sample(val name: String, val foreground: Int, val background: Int, val largeText: Boolean)

    fun samples(): List<Sample> = listOf(
        Sample("ocean-body", 0x0F172A, 0xF8FAFC, false),
        Sample("ocean-secondary", 0x64748B, 0xF8FAFC, false),
        Sample("ocean-button", 0xFFFFFF, 0x2563EB, true),
        Sample("ocean-bubble", 0x0F172A, 0xDBEAFE, false),
        Sample("ocean-dark-body", 0xE6ECF5, 0x0F172A, false),
        Sample("ocean-dark-button", 0x0F172A, 0x60A5FA, true),
        Sample("rose-body", 0x3D1B35, 0xFFF1F7, false),
        Sample("rose-secondary", 0x6B3A58, 0xFFF1F7, false),
        Sample("rose-button", 0xFFFFFF, 0xBE185D, true),
        Sample("rose-dark-body", 0xFFF1F7, 0x2A1220, false),
        Sample("rose-dark-button", 0x2A1220, 0xF9A8D4, true)
    )

    fun ratio(foreground: Int, background: Int): Double {
        val hi = maxOf(luminance(foreground), luminance(background))
        val lo = minOf(luminance(foreground), luminance(background))
        return (hi + 0.05) / (lo + 0.05)
    }

    fun passesAa(sample: Sample): Boolean {
        val need = if (sample.largeText) 3.0 else 4.5
        return ratio(sample.foreground, sample.background) >= need
    }

    private fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((rgb shr 16) and 0xFF)
        val g = channel((rgb shr 8) and 0xFF)
        val b = channel(rgb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

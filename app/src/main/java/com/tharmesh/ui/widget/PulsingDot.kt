// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import tharmesh.app.R
import com.tharmesh.ui.system.SystemStatus

/**
 * Stage 9.2 — small status dot used everywhere the user needs to know the
 * mesh is alive: brand header, chat list per-row, alerts top bar, relay top
 * bar. Property-only (alpha + radius), zero layout/measure passes once
 * inflated, drives off a single [ValueAnimator] driven by status — animator
 * is paused when status doesn't require motion (Connected = stable glow,
 * Offline = dim).
 *
 * Default size: 10dp × 10dp. Override via android:layout_width/height.
 *
 * Wire-up:
 *   `<com.tharmesh.ui.widget.PulsingDot ... />` in any layout, then:
 *
 *   ```kotlin
 *   pulsingDot.setStatus(SystemStatus.Searching)
 *   ```
 *
 * The dot fully detaches its animator in [onDetachedFromWindow] so it never
 * leaks Activity references when the host fragment is recycled.
 */
class PulsingDot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var status: SystemStatus = SystemStatus.Offline
    private var phase: Float = 0f
    private var animator: ValueAnimator? = null

    init {
        // Pre-resolve all four palette colours once so [draw] never has to
        // touch the theme on the UI thread.
        applyColorsFromTheme()
        // Default minimum size when used without explicit layout dimensions.
        // 10dp is the visual "feels right" size derived from the brand-header
        // mock; overridable via layout_width / layout_height.
        val dp10 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics
        ).toInt()
        minimumWidth = dp10
        minimumHeight = dp10
    }

    private var connectedColor: Int = 0
    private var searchingColor: Int = 0
    private var offlineColor: Int = 0
    private var degradedColor: Int = 0

    private fun applyColorsFromTheme() {
        connectedColor = resolveAttrColor(R.attr.tmDotConnected, "#00E676")
        searchingColor = resolveAttrColor(R.attr.tmDotSearching, "#00E5FF")
        offlineColor = resolveAttrColor(R.attr.tmDotOffline, "#5E6B87")
        degradedColor = resolveAttrColor(R.attr.tmDotDegraded, "#F0B070")
    }

    private fun resolveAttrColor(attrRes: Int, fallbackHex: String): Int {
        val tv = TypedValue()
        val resolved = context.theme.resolveAttribute(attrRes, tv, true)
        return if (resolved && tv.data != 0) tv.data else Color.parseColor(fallbackHex)
    }

    /**
     * Update the visual state. Cheap to call repeatedly with the same status
     * (idempotent — early-returns when the new state matches the current
     * one). Starts/stops the internal [ValueAnimator] as needed.
     */
    fun setStatus(newStatus: SystemStatus) {
        if (newStatus == status) return
        status = newStatus
        contentDescription = when (status) {
            SystemStatus.Connected -> context.getString(R.string.system_status_dot_cd_connected)
            SystemStatus.Searching -> context.getString(R.string.system_status_dot_cd_searching)
            SystemStatus.Connecting -> context.getString(R.string.system_status_dot_cd_connecting)
            SystemStatus.Offline -> context.getString(R.string.system_status_dot_cd_offline)
            SystemStatus.Degraded -> context.getString(R.string.system_status_dot_cd_degraded)
        }
        startOrStopAnimator()
        invalidate()
    }

    /** Visible-for-testing colour the dot core resolves to for [status]. */
    internal fun coreColorForCurrentStatus(): Int = when (status) {
        SystemStatus.Connected -> connectedColor
        SystemStatus.Searching -> searchingColor
        SystemStatus.Connecting -> searchingColor
        SystemStatus.Offline -> offlineColor
        SystemStatus.Degraded -> degradedColor
    }

    /** Visible-for-testing — does this status require a running animator? */
    internal fun isAnimatedStatus(s: SystemStatus = status): Boolean = when (s) {
        SystemStatus.Searching, SystemStatus.Connecting, SystemStatus.Degraded -> true
        SystemStatus.Connected, SystemStatus.Offline -> false
    }

    private fun startOrStopAnimator() {
        animator?.cancel()
        if (!isAnimatedStatus()) {
            phase = 0f
            return
        }
        val cycleMs = when (status) {
            SystemStatus.Connecting -> 900L  // faster cadence — the user is "actively connecting"
            SystemStatus.Searching -> 1400L
            SystemStatus.Degraded -> 1800L   // slower cadence — softer alarm
            else -> 1400L
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cycleMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val core = coreColorForCurrentStatus()
        // Halo: only drawn when status calls for motion. Pulses out to ~2x
        // the core radius and fades alpha 96 → 0 across the cycle.
        if (isAnimatedStatus()) {
            val haloAlpha = ((1f - phase) * 96f).toInt().coerceIn(0, 96)
            haloPaint.color = (haloAlpha shl 24) or (core and 0x00FFFFFF)
            val haloRadius = (cx.coerceAtMost(cy)) * (0.55f + 0.55f * phase)
            canvas.drawCircle(cx, cy, haloRadius, haloPaint)
        }
        // Core: solid dot, slight breathing scale on animated states.
        corePaint.color = core
        val coreRadiusBase = cx.coerceAtMost(cy) * 0.42f
        val coreScale = if (isAnimatedStatus()) 0.92f + 0.10f * phase else 1f
        canvas.drawCircle(cx, cy, coreRadiusBase * coreScale, corePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startOrStopAnimator()
    }
}

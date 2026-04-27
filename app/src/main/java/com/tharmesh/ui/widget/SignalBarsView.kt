// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import tharmesh.app.R

/**
 * Stage 9.2 — animated 4-bar signal indicator used on the alerts/network-health
 * card. Maps a peer count to "how many bars are lit" + drives a subtle phase-
 * offset breathing animation on the lit bars so the surface feels alive
 * without flashing or distracting the user.
 *
 * Mapping (deliberately conservative; spec calls for "live data feeling",
 * not literal RSSI):
 *
 *   peers <= 0  → 0 lit (all dim)
 *   peers == 1  → 1 lit
 *   peers == 2  → 2 lit
 *   peers == 3  → 3 lit
 *   peers >= 4  → 4 lit
 *
 * The bars at index N taller than index 0 (left=shortest, right=tallest) so
 * the "growing signal" affordance reads correctly. Property-only animator
 * — no layout/measure passes — drives a single [phase] that the [draw]
 * loop reads.
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barCount = 4
    private val barRect = RectF()

    private var phase: Float = 0f
    private var animator: ValueAnimator? = null
    private var litCount: Int = 0

    private var litColor: Int = 0
    private var dimColor: Int = 0

    init {
        applyColorsFromTheme()
        // Default minSize — keep small so the host card defines the actual
        // footprint via layout_width/height.
        val dp36 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics
        ).toInt()
        val dp24 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
        ).toInt()
        minimumWidth = dp36
        minimumHeight = dp24
    }

    private fun applyColorsFromTheme() {
        litColor = resolveAttrColor(R.attr.tmDotConnected, "#00E676")
        dimColor = resolveAttrColor(R.attr.tmDotOffline, "#5E6B87")
    }

    private fun resolveAttrColor(attrRes: Int, fallbackHex: String): Int {
        val tv = TypedValue()
        val resolved = context.theme.resolveAttribute(attrRes, tv, true)
        return if (resolved && tv.data != 0) tv.data else Color.parseColor(fallbackHex)
    }

    /**
     * Update bar count from a peer count. Defensive: clamps negative values
     * to 0 and saturates above the bar count. Idempotent — early-returns
     * when the resolved lit count matches the current state.
     */
    fun setPeerCount(peerCount: Int) {
        val newLit = peerCount.coerceIn(0, barCount)
        if (newLit == litCount) return
        litCount = newLit
        startOrStopAnimator()
        invalidate()
    }

    /** Visible-for-testing — count of currently lit bars after [setPeerCount]. */
    internal fun litCount(): Int = litCount

    /** Visible-for-testing — does the current lit count drive an animator? */
    internal fun isAnimating(): Boolean = animator?.isRunning == true

    private fun startOrStopAnimator() {
        animator?.cancel()
        if (litCount <= 0) {
            phase = 0f
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
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
        if (width == 0 || height == 0) return
        val gap = width * 0.10f / (barCount - 1).coerceAtLeast(1)
        val barWidth = (width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val tallness = 0.40f + 0.20f * i  // 0.40 → 1.00 across the 4 bars
            val baseHeight = height * tallness
            val isLit = i < litCount
            // Phase-offset breathing: each bar's individual height pulses
            // ±10% around the base, with a quarter-cycle stagger so the
            // four-bar group reads as a wave moving left → right.
            val phaseOffset = (phase + i * 0.25f) % 1f
            val breathing = if (isLit) {
                baseHeight * (0.92f + 0.10f * kotlin.math.sin(phaseOffset * 2 * Math.PI).toFloat())
            } else {
                baseHeight
            }
            val left = i * (barWidth + gap)
            val top = height - breathing
            barRect.set(left, top, left + barWidth, height.toFloat())
            barPaint.color = if (isLit) litColor else (dimColor and 0x00FFFFFF) or 0x44000000
            canvas.drawRoundRect(barRect, barWidth * 0.3f, barWidth * 0.3f, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (litCount > 0) startOrStopAnimator()
    }
}

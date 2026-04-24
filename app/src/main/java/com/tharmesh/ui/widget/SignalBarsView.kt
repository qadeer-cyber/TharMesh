package com.tharmesh.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * 16 animated vertical bars; bar heights shimmer to suggest live signal.
 * Pure onDraw + single ValueAnimator — cheap enough for the dashboard card.
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dimColor = Color.parseColor("#1A2238")
    private val strokeColor = Color.parseColor("#2A3757")
    private val barCount = 20
    private var phase = 0f

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val rect = RectF()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 3f
        val barW = (w - gap * (barCount - 1)) / barCount
        val radius = barW / 2f
        for (i in 0 until barCount) {
            val x = i * (barW + gap)
            // sin wave with per-bar phase offset
            val t = (phase * Math.PI * 2 + i * 0.35).toDouble()
            val amp = 0.35f + 0.45f * (0.5f + 0.5f * sin(t).toFloat())
            val barH = h * amp
            // Background track
            paint.color = dimColor
            rect.set(x, 0f, x + barW, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
            // Active bar — color fades from cyan at low bars to green at peak
            val peak = amp
            val color = lerp(Color.parseColor("#00E5FF"), Color.parseColor("#00E676"), peak)
            paint.color = color
            rect.set(x, h - barH, x + barW, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun lerp(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.argb(a, r, g, b)
    }
}

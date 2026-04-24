package com.tharmesh.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Purely-drawn mesh visualization: a glowing center node (us) with N peer nodes
 * orbiting it, connected by animated lines. No bitmaps, no libs — just a couple
 * of Paints and a ValueAnimator driving [phase] from 0..1.
 */
class MeshGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cyan = Color.parseColor("#00E5FF")
    private val green = Color.parseColor("#00E676")
    private val cyanDim = Color.parseColor("#5500E5FF")
    private val lineColor = Color.parseColor("#402D6FFF")
    private val stroke = Color.parseColor("#1E2A45")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = lineColor
    }
    private val lineHotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = cyanDim
    }
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A2238")
    }
    private val nodeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = cyan
    }
    private val nodeStrokeGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = green
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = stroke
    }
    // Class-level to avoid allocating a Paint on every onDraw frame under the infinite
    // ValueAnimator (onDraw fires >100 Hz, GC pressure otherwise).
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = green
    }

    private var phase = 0f
    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    // Up to 6 orbital slots; [peerCount] clamps how many are actually drawn so the graph
    // reflects the real number of online nodes instead of always showing a full mesh.
    private val peerAngles = floatArrayOf(0f, 60f, 120f, 180f, 240f, 300f)
    private val peerRadii = floatArrayOf(0.82f, 0.95f, 0.70f, 0.88f, 0.75f, 0.92f)
    private var peerCount: Int = 0

    /** Sets how many peer nodes to draw in the orbit (clamped to 0..6). */
    fun setPeerCount(count: Int) {
        val clamped = count.coerceIn(0, peerAngles.size)
        if (clamped == peerCount) return
        peerCount = clamped
        invalidate()
    }

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
        val cx = w / 2f
        val cy = h / 2f
        val maxR = minOf(cx, cy) - 24f

        // Soft cyan glow behind center
        val glowR = maxR * 0.55f
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            Color.parseColor("#6600E5FF"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        // Orbit rings (subtle)
        for (r in floatArrayOf(maxR * 0.45f, maxR * 0.75f, maxR)) {
            canvas.drawCircle(cx, cy, r, strokePaint)
        }

        val drawCount = peerCount.coerceIn(0, peerAngles.size)
        if (drawCount > 0) {
            // Evenly redistribute visible peers around the orbit so 1..5 nodes don't
            // cluster on one side.
            val step = 360f / drawCount
            val rotDeg = phase * 360f
            val peerPositions = FloatArray(drawCount * 2)
            for (i in 0 until drawCount) {
                val ang = Math.toRadians((i * step + rotDeg).toDouble())
                val r = maxR * peerRadii[i % peerRadii.size]
                val px = cx + (r * cos(ang)).toFloat()
                val py = cy + (r * sin(ang)).toFloat()
                peerPositions[i * 2] = px
                peerPositions[i * 2 + 1] = py
            }

            // Lines from center to each visible peer, with one hot pulse highlight.
            val hotIndex = ((phase * drawCount) % drawCount).toInt()
            for (i in 0 until drawCount) {
                val px = peerPositions[i * 2]
                val py = peerPositions[i * 2 + 1]
                val paint = if (i == hotIndex) lineHotPaint else linePaint
                canvas.drawLine(cx, cy, px, py, paint)
            }

            // Sparse peer-to-peer mesh links only when there are enough peers to connect.
            if (drawCount >= 3) {
                for (i in 0 until drawCount) {
                    val next = (i + 2) % drawCount
                    if (next == i) continue
                    canvas.drawLine(
                        peerPositions[i * 2],
                        peerPositions[i * 2 + 1],
                        peerPositions[next * 2],
                        peerPositions[next * 2 + 1],
                        linePaint
                    )
                }
            }

            // Draw peer nodes
            for (i in 0 until drawCount) {
                val px = peerPositions[i * 2]
                val py = peerPositions[i * 2 + 1]
                canvas.drawCircle(px, py, 10f, nodeFill)
                canvas.drawCircle(px, py, 10f, if (i % 2 == 0) nodeStroke else nodeStrokeGreen)
            }
        }

        // Pulsating center node
        val pulse = 1f + 0.15f * sin(phase * Math.PI * 2).toFloat()
        canvas.drawCircle(cx, cy, 18f * pulse, nodeFill)
        canvas.drawCircle(cx, cy, 18f * pulse, nodeStroke)
        // Inner green core (uses class-level [corePaint] — do not allocate in onDraw).
        canvas.drawCircle(cx, cy, 6f, corePaint)
    }
}

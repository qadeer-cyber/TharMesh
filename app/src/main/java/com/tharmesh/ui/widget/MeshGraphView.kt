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

    /**
     * Stage 9.2 — node-count change animator. When [setPeerCount] is called
     * with a value different from the current [peerCount], we kick off a
     * 320ms ValueAnimator that drives [countChangePhase] from 0 → 1. While
     * this is running, the "newly-revealed" nodes (indices in
     * [peerCount, prevPeerCount)) on shrink — or [prevPeerCount, peerCount)
     * on grow — render at a scale that eases between 0 and full so the
     * change reads as a soft "node joined / left the mesh", not a snap.
     */
    private var prevPeerCount: Int = 0
    private var countChangePhase: Float = 1f
    private var countAnimator: ValueAnimator? = null

    /** Sets how many peer nodes to draw in the orbit (clamped to 0..6). */
    fun setPeerCount(count: Int) {
        val clamped = count.coerceIn(0, peerAngles.size)
        if (clamped == peerCount) return
        prevPeerCount = peerCount
        peerCount = clamped
        countChangePhase = 0f
        countAnimator?.cancel()
        countAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = LinearInterpolator()
            addUpdateListener {
                countChangePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    /**
     * Stage 9.2 — when set to a positive value, the edge index modulo
     * peerCount is rendered with the hot paint regardless of the rotation
     * cursor. Used to "flash" the edge along which the most recently
     * relayed bundle travelled. Decays back to -1 after [highlightUntilMs].
     */
    private var highlightedEdgeIndex: Int = -1
    private var highlightUntilMs: Long = 0L

    /**
     * Trigger an active-edge highlight for the next 4 seconds. Pure UX
     * affordance — caller wires this up from
     * [com.tharmesh.data.OnAutoDeliveredOnReconnect] or any other "we just
     * relayed something" hook the host fragment knows about.
     */
    fun flashRelayActivity(peerIndex: Int) {
        if (peerCount <= 0) return
        highlightedEdgeIndex = peerIndex.coerceIn(0, peerCount - 1)
        highlightUntilMs = System.currentTimeMillis() + 4000L
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        countAnimator?.cancel()
        countAnimator = null
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
            // Stage 9.2: when the host flashed relay activity recently, that
            // specific peer's edge is forced hot — overrides the rotating
            // pulse cursor for up to 4s after [flashRelayActivity] is called.
            val hotIndex = ((phase * drawCount) % drawCount).toInt()
            val flashStillActive =
                highlightedEdgeIndex >= 0 && System.currentTimeMillis() < highlightUntilMs
            val effectiveHotIndex = if (flashStillActive) {
                highlightedEdgeIndex.coerceIn(0, drawCount - 1)
            } else {
                if (highlightedEdgeIndex >= 0) highlightedEdgeIndex = -1
                hotIndex
            }
            for (i in 0 until drawCount) {
                val px = peerPositions[i * 2]
                val py = peerPositions[i * 2 + 1]
                val paint = if (i == effectiveHotIndex) lineHotPaint else linePaint
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

            // Draw peer nodes. Stage 9.2 — each node breathes at a slightly
            // staggered phase so the orbit doesn't pulse in unison (would
            // read as machine rather than network). Newly-revealed nodes
            // ease their radius from 0 → full over the count-change phase.
            for (i in 0 until drawCount) {
                val px = peerPositions[i * 2]
                val py = peerPositions[i * 2 + 1]
                val nodePhase = (phase + i * 0.18f) % 1f
                val nodeBreath = 1f + 0.18f * sin(nodePhase * 2 * Math.PI).toFloat()
                // Scale-in for nodes that weren't there before the latest count change.
                val isNewNode = i >= prevPeerCount
                val growth = if (isNewNode && countChangePhase < 1f) countChangePhase else 1f
                val r = 10f * nodeBreath * growth
                canvas.drawCircle(px, py, r, nodeFill)
                canvas.drawCircle(px, py, r, if (i % 2 == 0) nodeStroke else nodeStrokeGreen)
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

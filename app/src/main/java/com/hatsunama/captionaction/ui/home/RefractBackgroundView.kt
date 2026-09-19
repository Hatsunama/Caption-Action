package com.hatsunama.captionaction.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.Choreographer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Locked Home refract background (home_refract_v7 / LOCKED).
 *
 * Live procedural Choreographer sim — never a video/image asset.
 * Blank charcoal canvas; bouncing nodes; RAYS_PER=5 hairline rays;
 * ethereal translucent films only in discrete wedges; per-node shuffled neon;
 * centers = vortex warp only (no disk/ring/sprite).
 *
 * Visual tradeoff vs Python per-pixel renderer: Canvas draws warped Path wedges
 * + hairlines by sampling the locked vortex_warp along each ray so films/rays
 * spiral into the drain. Mid-wedge glow is approximated with a radial gradient-
 * free flat film (etherealized palette @ FILM_ALPHA) rather than a per-pixel
 * gaussian — reads as stained-glass aurora without a CPU pixel loop.
 */
class RefractBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    // --- Locked tunables (home_refract_v7) ---
    companion object {
        private const val TAG = "RefractBG"
        const val RAYS_PER = 5
        private const val N_NODES_MIN = 5
        private const val N_NODES_MAX = 7
        private const val FILM_ALPHA = 0.16f
        private const val LINE_ALPHA = 0.88f
        // Slightly faster spin so wedges clearly rotate within 1–2s.
        private const val SPIN_SPEED = 0.14f // rev/s base
        private const val REF_W = 540f
        private const val VORTEX_RADIUS_REF = 78f
        private const val VORTEX_SWIRL = 6.2f
        // Python VORTEX_RADIAL_K=24 is per-pixel sample pull; Path approx uses angular swirl only.
        private const val DESAT = 0.22f
        private const val LUM_LIFT = 0.18f
        // 24 samples keeps motion obvious without freezing mid-tier GPUs.
        private const val RAY_SAMPLES = 24
        // Bounce speed range (px/s @ REF_W); scaled by width.
        private const val SPEED_MIN = 48f
        private const val SPEED_MAX = 90f
        private val BG = intArrayOf(10, 11, 14)
        private val NEON_PALETTE = arrayOf(
            intArrayOf(255, 20, 147),
            intArrayOf(0, 220, 200),
            intArrayOf(170, 40, 255),
            intArrayOf(240, 255, 40),
            intArrayOf(255, 120, 20),
            intArrayOf(40, 120, 255),
            intArrayOf(255, 40, 80),
            intArrayOf(80, 255, 80)
        )
    }

    private data class Node(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val spin0: Float,
        val spinOmega: Float,
        val palette: IntArray // etherealized ARGB, length RAYS_PER
    )

    private val nodes = ArrayList<Node>(N_NODES_MAX)
    private var simSeed: Long = 0L
    private var simW = 0
    private var simH = 0
    private var tSec = 0.0
    private var lastFrameNs = 0L
    private var animating = false
    private var choreographerAttached = false
    private var startLogged = false

    private val filmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb((LINE_ALPHA * 255).toInt(), 0, 0, 0)
    }
    private val wedgePath = Path()
    private val rayPath = Path()
    private val tmpRayA = FloatArray(RAY_SAMPLES * 2)
    private val tmpRayB = FloatArray(RAY_SAMPLES * 2)

    init {
        setWillNotDraw(false)
        // Background must never steal clicks from Home controls above.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        simSeed = RefractSeed.obtain(context)
    }

    /**
     * Freeze simulation clock; node state + t preserved (no reset).
     * Enabling always re-arms Choreographer even if [animating] was already true
     * (fixes callback-dropped / onPause–onResume / attach races).
     */
    fun setAnimating(enabled: Boolean) {
        if (enabled) {
            val was = animating
            animating = true
            if (!was) lastFrameNs = 0L
            ensureChoreographer()
        } else {
            if (!animating && !choreographerAttached) return
            animating = false
            detachChoreographer()
        }
    }

    fun isAnimating(): Boolean = animating

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && windowVisibility == VISIBLE) {
            setAnimating(true)
        }
    }

    override fun onDetachedFromWindow() {
        setAnimating(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) {
            syncAnimatingFromVisibility()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimatingFromVisibility()
    }

    private fun syncAnimatingFromVisibility() {
        val want = visibility == VISIBLE &&
            windowVisibility == VISIBLE &&
            isAttachedToWindow
        setAnimating(want)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (nodes.isEmpty() || simW != w || simH != h) {
            // Rebuild nodes for new size; keep t so pause/resume still continuous.
            // First layout only — avoid resetting bounce state on trivial relayouts
            // after nodes already exist at this size.
            if (nodes.isEmpty()) {
                simW = w
                simH = h
                makeNodes(w, h)
                // First layout: arm frame loop so motion starts even if attach
                // raced ahead of size / animating flag was stuck.
                if (visibility == VISIBLE && windowVisibility == VISIBLE && isAttachedToWindow) {
                    setAnimating(true)
                }
                postInvalidateOnAnimation()
            } else if (simW != w || simH != h) {
                // Scale positions into new bounds so rotate/resize doesn't teleport.
                val sx = w.toFloat() / simW.coerceAtLeast(1)
                val sy = h.toFloat() / simH.coerceAtLeast(1)
                for (nd in nodes) {
                    nd.x *= sx
                    nd.y *= sy
                    nd.vx *= sx
                    nd.vy *= sy
                }
                simW = w
                simH = h
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!animating || !isAttachedToWindow ||
            visibility != VISIBLE || windowVisibility != VISIBLE
        ) {
            // Drop attachment flag but do NOT leave animating=true with no callback
            // without a path to re-arm — setAnimating(true) always re-arms.
            choreographerAttached = false
            return
        }
        if (lastFrameNs != 0L) {
            var dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000.0
            // Clamp huge gaps (debugger / background) so freeze-on-pause semantics hold.
            if (dt > 0.05) dt = 0.05
            if (dt > 0.0 && simW > 0 && simH > 0 && nodes.isNotEmpty()) {
                stepNodes(dt.toFloat(), simW, simH)
                tSec += dt
            }
        }
        lastFrameNs = frameTimeNanos
        invalidate()
        postInvalidateOnAnimation()
        // Keep flag true across re-post so ensureChoreographer won't double-post
        // while a callback is already queued; clear only on stop paths above.
        choreographerAttached = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        canvas.drawColor(Color.rgb(BG[0], BG[1], BG[2]))
        if (nodes.isEmpty()) return

        val scale = w / REF_W
        val vortexR = VORTEX_RADIUS_REF * scale
        val maxR = hypot(w.toDouble(), h.toDouble()).toFloat() + vortexR
        val sector = (2.0 * PI / RAYS_PER).toFloat()
        val t = tSec.toFloat()

        // Films first (SRC_OVER), then hairlines — matches Python line_mask-after-films.
        for (nd in nodes) {
            val spin = nd.spin0 + nd.spinOmega * t
            val swirlSign = if (nd.spinOmega >= 0f) 1f else -1f
            for (i in 0 until RAYS_PER) {
                val angA = spin + i * sector
                val angB = spin + (i + 1) * sector
                sampleWarpedRay(nd.x, nd.y, angA, swirlSign, vortexR, maxR, tmpRayA)
                sampleWarpedRay(nd.x, nd.y, angB, swirlSign, vortexR, maxR, tmpRayB)
                wedgePath.reset()
                wedgePath.moveTo(tmpRayA[0], tmpRayA[1])
                for (s in 1 until RAY_SAMPLES) {
                    wedgePath.lineTo(tmpRayA[s * 2], tmpRayA[s * 2 + 1])
                }
                for (s in RAY_SAMPLES - 1 downTo 0) {
                    wedgePath.lineTo(tmpRayB[s * 2], tmpRayB[s * 2 + 1])
                }
                wedgePath.close()
                filmPaint.color = nd.palette[i]
                canvas.drawPath(wedgePath, filmPaint)
            }
        }

        for (nd in nodes) {
            val spin = nd.spin0 + nd.spinOmega * t
            val swirlSign = if (nd.spinOmega >= 0f) 1f else -1f
            for (i in 0 until RAYS_PER) {
                val ang = spin + i * sector
                sampleWarpedRay(nd.x, nd.y, ang, swirlSign, vortexR, maxR, tmpRayA)
                rayPath.reset()
                rayPath.moveTo(tmpRayA[0], tmpRayA[1])
                for (s in 1 until RAY_SAMPLES) {
                    rayPath.lineTo(tmpRayA[s * 2], tmpRayA[s * 2 + 1])
                }
                canvas.drawPath(rayPath, linePaint)
            }
        }
        // No center drawable — vortex is warp only (locked).
    }

    /**
     * Sample screen-space curve of a constant warped-angle ray (locked vortex_warp).
     * Forward warp: θ' = θ + swirl * f(r); invert for drawing: θ = θ' − swirl * f(r).
     */
    private fun sampleWarpedRay(
        cx: Float,
        cy: Float,
        angW: Float,
        swirlSign: Float,
        vortexR: Float,
        maxR: Float,
        out: FloatArray
    ) {
        val n = RAY_SAMPLES
        for (s in 0 until n) {
            // Start slightly off-center so charcoal peeks through (no solid core).
            val r = 1.5f + (maxR - 1.5f) * (s.toFloat() / (n - 1).coerceAtLeast(1))
            val u = (1f - r / vortexR).coerceIn(0f, 1f)
            val strength = u * u
            val f = strength * (vortexR / (r + 6f))
            val ang = angW - swirlSign * VORTEX_SWIRL * (f / 10f)
            out[s * 2] = cx + r * cos(ang)
            out[s * 2 + 1] = cy + r * sin(ang)
        }
    }

    private fun makeNodes(w: Int, h: Int) {
        nodes.clear()
        val rng = Random(simSeed)
        val n = rng.nextInt(N_NODES_MIN, N_NODES_MAX + 1)
        val margin = 40f * (w / REF_W)
        val base = NEON_PALETTE.toMutableList()
        while (base.size < RAYS_PER) {
            base.add(NEON_PALETTE[base.size % NEON_PALETTE.size])
        }
        val speedScale = w / REF_W
        for (i in 0 until n) {
            val speed = rng.nextFloat() * (SPEED_MAX - SPEED_MIN) + SPEED_MIN
            val ang = rng.nextFloat() * (2f * PI.toFloat())
            val palSrc = base.take(RAYS_PER).toMutableList()
            // Fisher–Yates shuffle
            for (j in palSrc.lastIndex downTo 1) {
                val k = rng.nextInt(j + 1)
                val tmp = palSrc[j]
                palSrc[j] = palSrc[k]
                palSrc[k] = tmp
            }
            val palette = IntArray(RAYS_PER) { wi ->
                etherealArgb(palSrc[wi][0], palSrc[wi][1], palSrc[wi][2])
            }
            val spinSign = if (rng.nextFloat() > 0.5f) 1f else -1f
            val spinOmega = (rng.nextFloat() * (1.2f - 0.5f) + 0.5f) *
                SPIN_SPEED * 2f * PI.toFloat() * spinSign
            nodes.add(
                Node(
                    x = rng.nextFloat() * (w - 2 * margin) + margin,
                    y = rng.nextFloat() * (h - 2 * margin) + margin,
                    vx = cos(ang) * speed * speedScale,
                    vy = sin(ang) * speed * speedScale,
                    spin0 = rng.nextFloat() * (2f * PI.toFloat()),
                    spinOmega = spinOmega,
                    palette = palette
                )
            )
        }
    }

    private fun stepNodes(dt: Float, w: Int, h: Int) {
        val margin = 8f * (w / REF_W)
        for (nd in nodes) {
            nd.x += nd.vx * dt
            nd.y += nd.vy * dt
            if (nd.x < margin) {
                nd.x = margin
                nd.vx = abs(nd.vx)
            } else if (nd.x > w - margin) {
                nd.x = w - margin
                nd.vx = -abs(nd.vx)
            }
            if (nd.y < margin) {
                nd.y = margin
                nd.vy = abs(nd.vy)
            } else if (nd.y > h - margin) {
                nd.y = h - margin
                nd.vy = -abs(nd.vy)
            }
        }
    }

    /** Slight desat + luminosity lift → stained-glass / aurora (locked etherealize). */
    private fun etherealArgb(r: Int, g: Int, b: Int): Int {
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        val rd = r * (1f - DESAT) + lum * DESAT
        val gd = g * (1f - DESAT) + lum * DESAT
        val bd = b * (1f - DESAT) + lum * DESAT
        val rl = rd + (255f - rd) * LUM_LIFT
        val gl = gd + (255f - gd) * LUM_LIFT
        val bl = bd + (255f - bd) * LUM_LIFT
        // Flat-ish interior glow factor ~0.85 mid of Python's 0.70+0.30*mid_glow
        val a = (FILM_ALPHA * 0.85f * 255f).toInt().coerceIn(0, 255)
        return Color.argb(a, rl.toInt().coerceIn(0, 255), gl.toInt().coerceIn(0, 255), bl.toInt().coerceIn(0, 255))
    }

    private fun ensureChoreographer() {
        if (choreographerAttached) return
        choreographerAttached = true
        if (!startLogged) {
            startLogged = true
            Log.d(TAG, "frame loop armed")
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun detachChoreographer() {
        if (!choreographerAttached) return
        choreographerAttached = false
        Choreographer.getInstance().removeFrameCallback(this)
        lastFrameNs = 0L
    }
}

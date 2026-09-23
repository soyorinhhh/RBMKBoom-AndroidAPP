package com.example.reactor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.reactor.config.ReactorConfig

class ReactorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    class Rod {
        var x = 0f; var w = 0f
        var v = 0f; var vel = 0f
        var ox = 0f; var oy = 0f; var rot = 0f
        var vx = 0f; var vy = 0f; var rv = 0f
    }

    private var rods = ArrayList<Rod>()
    private var pressure = 0f
    private var flash = 0f
    private var overload = 0f
    private var exploded = false
    private var explodeT = 0f
    private var shake = 0f
    private var bgLayer: Bitmap? = null

    private var spring = 0.34f
    private var damping = 0.68f
    private var colorLow = 0xFF4DFFA6.toInt()
    private var colorMid = 0xFFFFB454.toInt()
    private var colorHigh = 0xFFFF3B30.toInt()
    private var rodSpacingDp = 21f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    fun applyConfig(cfg: ReactorConfig) {
        spring = cfg.feel.spring
        damping = cfg.feel.damping
        colorLow = cfg.theme.low
        colorMid = cfg.theme.mid
        colorHigh = cfg.theme.high
        val newSpacing = cfg.rodDensity.spacingDp
        if (newSpacing != rodSpacingDp) {
            rodSpacingDp = newSpacing
            if (width > 0) buildRods(width)
        }
        invalidate()
    }

    fun setBackgroundBitmap(src: Bitmap?) {
        if (src == null) { bgLayer = null; invalidate(); return }
        if (width <= 0 || height <= 0) { post { setBackgroundBitmap(src) }; return }
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        val s = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((width - src.width * s) / 2f, (height - src.height * s) / 2f)
        }
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawColor(0x88000000.toInt())
        bgLayer = layer
        invalidate()
    }

    fun update(s: FloatArray, dt: Float) {
        val n = rods.size
        if (n == 0 || s.isEmpty()) return
        var sum = 0f
        for (i in 0 until n) {
            val r = rods[i]
            val idx = i * s.size / n
            val target = (s[idx] * 1.55f).coerceIn(0f, 1.15f)
            r.vel += (target - r.v) * spring
            r.vel *= damping
            r.v += r.vel
            if (r.v < 0f) { r.v = 0f; r.vel *= -0.25f }
            if (r.v > 1.15f) { r.v = 1.15f; r.vel *= -0.25f }
            sum += r.v
        }
        val avg = sum / n
        val targetP = (avg / 0.78f).coerceAtMost(1f)
        pressure += (targetP - pressure) * minOf(1f, dt * 7f)
        val targetShake = ((pressure - 0.52f).coerceAtLeast(0f)) * 30f
        shake += (targetShake - shake) * minOf(1f, dt * 9f)

        if (pressure > 0.84f && !exploded) {
            overload += dt
            if (overload > 1.15f) triggerExplosion()
        } else {
            overload = (overload - dt * 1.6f).coerceAtLeast(0f)
        }
        if (flash > 0f) flash = (flash - dt * 1.45f).coerceAtLeast(0f)

        if (exploded) {
            explodeT += dt
            for (r in rods) {
                r.vy += 950f * dt
                r.ox += r.vx * dt
                r.oy += r.vy * dt
                r.rot += r.rv * dt
            }
            if (explodeT > 3.2f) reset()
        }
        invalidate()
    }

    private fun triggerExplosion() {
        exploded = true; explodeT = 0f; flash = 1f; overload = 0f; pressure = 1f
        for (r in rods) {
            val dir = if (Math.random() < 0.5) -1f else 1f
            r.vx = dir * (120f + Math.random().toFloat() * 620f)
            r.vy = -(320f + Math.random().toFloat() * 780f)
            r.rv = ((Math.random() - 0.5) * 16).toFloat()
            r.ox = 0f; r.oy = 0f; r.rot = 0f
        }
    }

    fun reset() {
        exploded = false; explodeT = 0f; flash = 0f
        pressure = 0f; overload = 0f; shake = 0f
        for (r in rods) {
            r.v = 0f; r.vel = 0f
            r.ox = 0f; r.oy = 0f; r.rot = 0f
            r.vx = 0f; r.vy = 0f; r.rv = 0f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildRods(w)
    }

    private fun buildRods(w: Int) {
        val n = (w / (rodSpacingDp * density)).toInt().coerceIn(16, 54)
        val pad = maxOf(20f * density, w * 0.045f)
        val sp = (w - pad * 2) / n
        val old = rods
        val next = ArrayList<Rod>(n)
        for (i in 0 until n) {
            val r = Rod()
            val prev = if (old.isNotEmpty()) old[i * old.size / n] else null
            if (prev != null) { r.v = prev.v; r.vel = prev.vel }
            r.x = pad + sp * (i + 0.5f)
            r.w = maxOf(3f * density, sp * 0.5f)
            next.add(r)
        }
        rods = next
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bgLayer?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        canvas.save()
        if (shake > 0.2f) {
            canvas.translate(
                (Math.random() - 0.5).toFloat() * shake,
                (Math.random() - 0.5).toFloat() * shake
            )
        }
        drawRods(canvas, h)
        canvas.restore()

        if (flash > 0f) {
            fillPaint.color = Color.argb((flash * 235).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }
        if (overload > 0.02f) {
            val blink = 0.55f + 0.45f * kotlin.math.sin(System.currentTimeMillis() / 55.0).toFloat()
            val a = minOf(0.3f, overload * 0.26f) * blink
            fillPaint.color = Color.argb((a * 255).toInt(), 255, 25, 20)
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }
    }

    private fun drawRods(canvas: Canvas, h: Float) {
        val coreTop = h * 0.075f
        val coreBottom = h * 0.945f
        val coreH = coreBottom - coreTop
        val rodTop = coreTop + coreH * 0.035f
        val restLen = coreH * 0.74f
        val travel = coreH * 0.55f
        val rr = 4f * density

        for (r in rods) {
            val amp = r.v.coerceIn(0f, 1.15f)
            val len = maxOf(10f, restLen - amp * travel)
            val wd = r.w
            canvas.save()
            canvas.translate(r.x + r.ox, rodTop + r.oy)
            if (exploded) canvas.rotate(r.rot)

            val col = rodColor(amp)

            glowPaint.color = col
            glowPaint.alpha = ((0.18f + amp * 0.22f) * 255).toInt().coerceIn(0, 255)
            val gw = wd * 0.92f
            canvas.drawRoundRect(
                RectF(-gw, -wd * 0.4f, gw, len + wd * 0.9f),
                minOf(gw, rr * 2), minOf(gw, rr * 2), glowPaint
            )

            fillPaint.color = col
            fillPaint.alpha = 255
            canvas.drawRoundRect(RectF(-wd / 2, 0f, wd / 2, len), rr, rr, fillPaint)

            fillPaint.color = Color.WHITE
            fillPaint.alpha = 76
            canvas.drawRoundRect(
                RectF(-wd * 0.24f, minOf(wd, len * 0.12f),
                      -wd * 0.24f + wd * 0.26f, minOf(wd, len * 0.12f) + maxOf(2f, len * 0.72f)),
                rr * 0.5f, rr * 0.5f, fillPaint
            )

            fillPaint.color = Color.WHITE
            fillPaint.alpha = (56 + amp * 184).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, len, maxOf(1.4f, wd * 0.44f), fillPaint)

            fillPaint.color = 0xFF8CBEB0.toInt()
            fillPaint.alpha = 140
            canvas.drawRoundRect(
                RectF(-wd * 0.85f, -wd * 0.75f, wd * 0.85f, 0f),
                rr * 0.4f, rr * 0.4f, fillPaint
            )

            canvas.restore()
        }
    }

    private fun rodColor(v: Float): Int {
        val t = v.coerceIn(0f, 1.05f) / 1.05f
        return if (t < 0.5f) lerpColor(colorLow, colorMid, t / 0.5f)
               else lerpColor(colorMid, colorHigh, (t - 0.5f) / 0.5f)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
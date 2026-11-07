package com.example.reactor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.example.reactor.config.ReactorConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * REACTOR 声场：横向椭圆、密集立体模块阵列。
 *
 * 结构：
 * - 极坐标网格（环 × 扇区）铺在一个被压扁的椭圆平面上，形成透视；
 * - 每个模块是「顶面 + 正面」两条矩形，正面随频段升降，顶面露出一点厚度，形成 2.5D 立体感；
 * - 中心低频、外围高频，再加一点角向抖动，让同一环内的模块也有差异；
 * - 按深度从后往前绘制，近处模块更大更亮，强化透视。
 *
 * 性能：
 * - 几何在 onSizeChanged / 换密度时一次性算好，绘制时只更新高度；
 * - 不排序、不建对象、不建 Path，全部 drawRect；
 * - 颜色用 int 位运算插值，不产生临时对象。
 */
class ReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private class Node {
        var baseX = 0f
        var baseY = 0f
        var halfW = 1f
        var capHeight = 1f
        var band = 0
        var scale = 1f
        var depth = 0.5f
        var seed = 0
        var z = 0f
        var vz = 0f
    }

    private val density = resources.displayMetrics.density
    private val nodes = ArrayList<Node>()
    private var maxHeight = 40f
    private var centerX = 0f
    private var centerY = 0f
    private var fieldRadiusX = 0f
    private var fieldRadiusY = 0f

    private var spring = 0.34f
    private var damping = 0.68f
    private var theme: ReactorConfig.Theme = ReactorConfig.Theme.GREEN
    private var bandCount = 48
    private var densityPreset: ReactorConfig.RodDensity = ReactorConfig.RodDensity.NORMAL

    private var bgLayer: Bitmap? = null
    private var bgAlpha = 0.55f

    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var floorShader: RadialGradient? = null

    /** 当前顶面/正面基准色（随主题变化）。 */
    private var topBase = 0xFF44594A.toInt()
    private var sideBase = 0xFF16241B.toInt()
    private var accent = 0xFFC9A227.toInt()
    private var glow = 0xFF6FE1A0.toInt()

    private var pressure = 0f
    private var shake = 0f
    private var flash = 0f

    /** 椭圆底盘 / 整体抖动，两个都可以在设置里关掉（默认关）。 */
    private var showChassis = false
    private var shakeEnabled = false

    private var dirtyGeometry = true

    fun applyConfig(cfg: ReactorConfig) {
        spring = cfg.feel.spring
        damping = cfg.feel.damping
        theme = cfg.theme
        bandCount = cfg.bandCount
        showChassis = cfg.chassis
        shakeEnabled = cfg.shake
        if (!shakeEnabled) shake = 0f
        applyTheme(cfg.theme)
        if (densityPreset != cfg.rodDensity) {
            densityPreset = cfg.rodDensity
            dirtyGeometry = true
            requestGeometryRebuild()
        }
        invalidate()
    }

    private fun applyTheme(t: ReactorConfig.Theme) {
        when (t) {
            ReactorConfig.Theme.GREEN -> {
                topBase = 0xFF4A6350.toInt(); sideBase = 0xFF14201A.toInt()
                accent = 0xFFC9A227.toInt(); glow = 0xFF7FE0A8.toInt()
            }
            ReactorConfig.Theme.COBALT -> {
                topBase = 0xFF35506B.toInt(); sideBase = 0xFF0E1722.toInt()
                accent = 0xFF8FB7FF.toInt(); glow = 0xFF6FA8FF.toInt()
            }
            ReactorConfig.Theme.AMBER -> {
                topBase = 0xFF6B5233.toInt(); sideBase = 0xFF22160B.toInt()
                accent = 0xFFFFC24A.toInt(); glow = 0xFFFFB454.toInt()
            }
            ReactorConfig.Theme.CRT -> {
                topBase = 0xFF3F6B52.toInt(); sideBase = 0xFF0C1610.toInt()
                accent = 0xFFD4FF7F.toInt(); glow = 0xFF7FFFAA.toInt()
            }
            ReactorConfig.Theme.MONO -> {
                topBase = 0xFF55585C.toInt(); sideBase = 0xFF18191B.toInt()
                accent = 0xFFE8E8E8.toInt(); glow = 0xFFCCCCCC.toInt()
            }
        }
        floorShader = null
    }

    fun setBackgroundBitmap(src: Bitmap?) {
        if (src == null) {
            bgLayer = null
            invalidate()
            return
        }
        if (width <= 0 || height <= 0) {
            post { setBackgroundBitmap(src) }
            return
        }
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layer)
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - src.width * scale) / 2f, (height - src.height * scale) / 2f)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawColor(0x77000000)
        bgLayer = layer
        invalidate()
    }

    fun setBackgroundAlpha(alpha: Float) {
        bgAlpha = alpha.coerceIn(0f, 1f)
        invalidate()
    }

    fun reset() {
        flash = 0f
        pressure = 0f
        shake = 0f
        for (node in nodes) {
            node.z = 0f
            node.vz = 0f
        }
        invalidate()
    }

    fun triggerFlash() {
        flash = 1f
    }

    /**
     * 推进一帧。
     * @param s 48 频段 0..1
     * @param dt 秒
     */
    fun update(s: FloatArray, dt: Float) {
        if (dirtyGeometry) requestGeometryRebuild()
        val n = nodes.size
        if (n == 0 || s.isEmpty()) return

        val step = dt.coerceIn(0.004f, 0.2f)
        val bands = minOf(bandCount, s.size)
        var sum = 0f

        for (i in 0 until n) {
            val node = nodes[i]
            val amp = s[if (node.band < bands) node.band else bands - 1]

            // 每个模块加一点点个体差异，避免整环齐刷刷地动
            val jitter = 1f + ((node.seed and 7) - 3) * 0.018f
            // 0.72 次方让小声也有明显起伏，同时不至于满屏顶格
            val shaped = if (amp <= 0f) 0f else Math.pow(amp.toDouble(), 0.72).toFloat()
            val target = (shaped * maxHeight * jitter).coerceAtMost(maxHeight * 1.2f)

            node.vz += (target - node.z) * spring
            node.vz *= damping
            node.z += node.vz
            if (node.z < 0f) {
                node.z = 0f
                node.vz *= -0.2f
            }
            sum += node.z
        }

        val avg = sum / n
        val targetPressure = (avg / (maxHeight * 0.8f)).coerceIn(0f, 1f)
        pressure += (targetPressure - pressure) * minOf(1f, step * 8f)
        // 关掉抖动时不累加位移，直接归零，免得下次打开时先弹一下
        val targetShake = if (shakeEnabled) {
            ((pressure - 0.72f).coerceAtLeast(0f)) * 14f * density
        } else {
            0f
        }
        shake += (targetShake - shake) * minOf(1f, step * 10f)
        if (flash > 0f) flash = (flash - step * 2.2f).coerceAtLeast(0f)

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dirtyGeometry = true
        rebuildGeometry()
    }

    private fun requestGeometryRebuild() {
        if (width <= 0 || height <= 0) return
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return
        nodes.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        centerX = w / 2f
        centerY = h * 0.52f

        val preset = densityPreset
        val rings = preset.rings
        val sectors = preset.sectors

        fieldRadiusX = minOf(w * 0.46f, h * 0.92f)
        fieldRadiusY = fieldRadiusX * 0.44f
        if (fieldRadiusY > h * 0.34f) {
            fieldRadiusY = h * 0.34f
            fieldRadiusX = fieldRadiusY / 0.44f
        }
        maxHeight = fieldRadiusX * 0.40f

        val twoPi = (2.0 * PI).toFloat()
        for (ring in 0 until rings) {
            val r01 = if (rings == 1) 0.5f else ring / (rings - 1f)
            // 内环少些模块，外环多些，密度接近圆环周长
            val ringSectors = (sectors * (0.42f + 0.58f * r01)).roundToInt().coerceAtLeast(6)
            val ringRadiusX = fieldRadiusX * r01
            val ringRadiusY = fieldRadiusY * r01
            val circumference = twoPi * ringRadiusX
            val cellW = (circumference / ringSectors).coerceIn(2f, fieldRadiusX * 0.18f)

            for (sector in 0 until ringSectors) {
                val angle = twoPi * sector / ringSectors
                val sinA = sin(angle)
                val cosA = cos(angle)
                val depth = (sinA + 1f) * 0.5f

                val node = Node()
                node.baseX = centerX + ringRadiusX * cosA
                node.baseY = centerY + ringRadiusY * sinA
                // 近处（sinA>0，屏幕下方）更大更亮
                node.scale = 0.72f + 0.42f * depth
                node.halfW = cellW * 0.28f * node.scale
                node.capHeight = (cellW * 0.36f * node.scale).coerceAtLeast(1.0f * density)
                node.depth = depth
                node.seed = sector * 31 + ring * 17

                // 中心低频 → 外围高频；再叠加角向抖动 + 每个模块的固定抖动，
                // 避免整环同高，看起来像同心圆条纹
                val wobble = sinA * 3f + cosA * 2f
                val jitter = ((ring * 7 + sector * 13) % 9 - 4) * 0.9f
                val rawBand = r01 * (bandCount - 1) + wobble * r01 * 4.5f + jitter
                node.band = rawBand.roundToInt().coerceIn(0, bandCount - 1)

                node.z = 0f
                node.vz = 0f
                nodes.add(node)
            }
        }

        // 从后往前画：baseY 越大越靠前，后画
        nodes.sortWith(compareBy { it.baseY })

        floorShader = null
        dirtyGeometry = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bgLayer?.let { bitmap ->
            val keep = sidePaint.alpha
            sidePaint.alpha = (bgAlpha * 255).toInt()
            canvas.drawBitmap(bitmap, 0f, 0f, sidePaint)
            sidePaint.alpha = keep
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.save()
        if (shake > 0.15f) {
            canvas.translate(
                ((noise01() - 0.5f) * shake),
                ((noise01() - 0.5f) * shake * 0.5f)
            )
        }

        drawFloor(canvas)

        for (i in 0 until nodes.size) {
            val node = nodes[i]
            val height = node.z
            if (height < 0.35f && node.halfW < 0.6f) continue

            val topY = node.baseY - height
            val baseY = node.baseY
            val level = (height / (maxHeight * 0.8f)).coerceIn(0f, 1f)

            // 正面：越靠前越亮，越高越亮，形成深度感
            sidePaint.color = sideColor(node.depth, node.scale, level)
            canvas.drawRect(
                node.baseX - node.halfW, topY,
                node.baseX + node.halfW, baseY,
                sidePaint
            )

            // 顶面：露出一点厚度，形成「盖子」
            val cap = node.capHeight * (1f + height / (maxHeight * 5f))
            topPaint.color = capColor(node, level)
            canvas.drawRect(
                node.baseX - node.halfW, topY - cap,
                node.baseX + node.halfW, topY,
                topPaint
            )
        }

        drawFieldGlow(canvas)
        canvas.restore()

        if (flash > 0f) {
            topPaint.color = Color.argb((flash * 130).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, topPaint)
        }
    }

    /** 椭圆底盘 + 柔光，只覆盖声场范围，不铺满整窗（保持悬浮窗透明）。 */
    private fun drawFloor(canvas: Canvas) {
        if (!showChassis) return
        if (fieldRadiusX <= 0f) return
        val shader = floorShader ?: RadialGradient(
            centerX, centerY, fieldRadiusX,
            intArrayOf(
                Color.argb(70, 20, 40, 26),
                Color.argb(34, 14, 26, 18),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        ).also { floorShader = it }

        floorPaint.shader = shader
        canvas.save()
        canvas.scale(1f, fieldRadiusY / fieldRadiusX, centerX, centerY)
        canvas.drawCircle(centerX, centerY, fieldRadiusX, floorPaint)
        canvas.restore()
        floorPaint.shader = null
    }

    private fun drawFieldGlow(canvas: Canvas) {
        if (pressure <= 0.02f) return
        topPaint.color = Color.argb((pressure * 46).toInt().coerceIn(0, 255), glow and 0xFF0000 shr 16, glow shr 8 and 0xFF, glow and 0xFF)
        canvas.drawCircle(centerX, centerY, fieldRadiusX * (0.9f + pressure * 0.25f), topPaint)
    }

    private fun capColor(node: Node, level: Float): Int {
        // 越高越亮；接近满幅时混入琥珀色点缀
        var color = mix(topBase, glow, level * 0.60f)
        // 少量模块固定带一点琥珀，制造参考图里星星点点的暖色
        if ((node.seed / 7) % 9 == 0) color = mix(color, accent, 0.35f)
        if (level > 0.66f) color = mix(color, accent, (level - 0.66f) / 0.34f * 0.70f)
        return shade(color, 0.80f + 0.30f * node.depth)
    }

    /** 侧面：深度 + 高度双重提亮，让立体感和远近都能看出来。 */
    private fun sideColor(depth: Float, scale: Float, level: Float): Int =
        shade(sideBase, (0.70f + 0.45f * depth) * scale * (0.72f + 0.38f * level))

    private fun shade(base: Int, scale: Float): Int {
        val f = scale.coerceIn(0.25f, 1.35f)
        val r = ((base shr 16 and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = ((base shr 8 and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((base and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val ar = a shr 16 and 0xFF; val ag = a shr 8 and 0xFF; val ab = a and 0xFF
        val br = b shr 16 and 0xFF; val bg = b shr 8 and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * k).toInt()
        val g = (ag + (bg - ag) * k).toInt()
        val bl = (ab + (bb - ab) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** 抖动用的轻量伪随机，避免 Math.random() 每帧建对象。 */
    private var noiseState = 0x9E3779B9.toInt()
    private fun noise01(): Float {
        noiseState = noiseState xor (noiseState shl 13)
        noiseState = noiseState xor (noiseState ushr 17)
        noiseState = noiseState xor (noiseState shl 5)
        return ((noiseState ushr 8) and 0xFFFF) / 65535f
    }
}

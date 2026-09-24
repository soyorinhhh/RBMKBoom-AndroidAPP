package com.example.reactor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.reactor.config.ReactorConfig
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ReactorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // 3D 场景里的单个方块节点
    class Node {
        var angle = 0f        // 极坐标角度
        var radiusRatio = 0f  // 距离中心的半径比例 (0.0 中心 -> 1.0 边缘)
        var x3d = 0f          // 预计算的 3D 坐标
        var y3d = 0f
        var z = 0f            // 高度（音频驱动）
        var vz = 0f           // 高度速度
    }

    private var nodes = ArrayList<Node>()
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

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    // 3D 场景配置
    private val ringCount = 7
    private val maxRadius = 320f * density
    private val tiltAngle = 55f * (Math.PI / 180f).toFloat() // 倾斜 55 度

    fun applyConfig(cfg: ReactorConfig) {
        spring = cfg.feel.spring
        damping = cfg.feel.damping
        colorLow = cfg.theme.low
        colorMid = cfg.theme.mid
        colorHigh = cfg.theme.high
        // 密度在 3D 模式下由环形分布固定，这里留空
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

    // 音频数据更新 -> 物理运动 -> 重绘
    fun update(s: FloatArray, dt: Float) {
        val n = nodes.size
        if (n == 0 || s.isEmpty()) return
        var sum = 0f
        for (i in 0 until n) {
            val node = nodes[i]
            // 内圈（radiusRatio小）分配低频，外圈分配高频
            val bandIdx = (node.radiusRatio * (s.size - 1)).toInt().coerceIn(0, s.size - 1)
            val targetZ = (s[bandIdx] * 150f).coerceIn(0f, 200f) // 高度缩放

            node.vz += (targetZ - node.z) * spring
            node.vz *= damping
            node.z += node.vz
            
            // 防止穿模
            if (node.z < 0f) { node.z = 0f; node.vz *= -0.25f }
            sum += node.z
        }
        val avg = sum / n
        val targetP = (avg / 100f).coerceIn(0f, 1f)
        pressure += (targetP - pressure) * minOf(1f, dt * 7f)
        val targetShake = ((pressure - 0.5f).coerceAtLeast(0f)) * 20f
        shake += (targetShake - shake) * minOf(1f, dt * 9f)

        if (flash > 0f) flash = (flash - dt * 1.45f).coerceAtLeast(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildNodes()
    }

    // 构建 3D 网格节点
    private fun buildNodes() {
        nodes.clear()
        val centerX = width / 2f
        val centerY = height * 0.75f // 稍微往下移，制造纵深感

        for (r in 1..ringCount) {
            val radiusRatio = r.toFloat() / ringCount
            val count = (6 + r * 6) // 越往外圈，方块越多
            for (i in 0 until count) {
                val node = Node()
                node.radiusRatio = radiusRatio
                node.angle = (i.toFloat() / count) * 2f * Math.PI.toFloat()
                
                // 计算 3D 坐标
                val radius = maxRadius * radiusRatio
                node.x3d = radius * cos(node.angle)
                node.y3d = radius * sin(node.angle)
                nodes.add(node)
            }
        }
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

        val centerX = w / 2f
        val centerY = h * 0.75f

        // 根据距离（y3d）排序，越远越先画（保证遮挡关系正确）
        nodes.sortWith(Comparator { a, b -> a.y3d.compareTo(b.y3d) })

        val cosTilt = cos(tiltAngle)
        val sinTilt = sin(tiltAngle)

        for (node in nodes) {
            // 3D 投射到 2D（绕 X 轴旋转）
            val screenX = centerX + node.x3d
            val screenY = centerY + node.y3d * cosTilt - node.z * sinTilt

            // 深度雾化效果
            val depthFactor = (node.y3d / maxRadius + 1f) / 2f // 0(远) -> 1(近)
            val alpha = (0.2f + depthFactor * 0.8f).coerceIn(0f, 1f)

            // 方块大小根据深度和高度变化
            val size = (2f + depthFactor * 4f) * density * (1f + node.z / 150f)

            // 根据高度计算颜色（模仿热力图）
            val col = rodColor(node.z / 150f)
            fillPaint.color = col
            fillPaint.alpha = (alpha * 255).toInt()
            
            canvas.drawRect(
                screenX - size / 2,
                screenY - size / 2,
                screenX + size / 2,
                screenY + size / 2,
                fillPaint
            )
            
            // 发光效果（只在高度高的时候显示）
            if (node.z > 50f) {
                glowPaint.color = col
                glowPaint.alpha = ((node.z - 50f) / 150f * 100).toInt().coerceIn(0, 100)
                canvas.drawCircle(screenX, screenY, size * 2f, glowPaint)
            }
        }

        // 中心光晕
        val glowRadius = maxRadius * 1.2f * (1f + pressure)
        val radial = RadialGradient(
            centerX, centerY, glowRadius,
            intArrayOf(Color.argb((30 * pressure).toInt(), 77, 255, 166), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        fillPaint.shader = radial
        canvas.drawCircle(centerX, centerY, glowRadius, fillPaint)
        fillPaint.shader = null

        canvas.restore()

        if (flash > 0f) {
            fillPaint.color = Color.argb((flash * 235).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }
    }

    private fun rodColor(v: Float): Int {
        val t = v.coerceIn(0f, 1f)
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
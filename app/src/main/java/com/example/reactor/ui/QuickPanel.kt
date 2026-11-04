package com.example.reactor.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.reactor.audio.SourceKind

/**
 * 长按小窗弹出的快捷面板。
 *
 * 相比初版：配色跟主题统一（暗绿工业风），按当前音频源高亮对应行，
 * 弹出位置会做屏幕边界收敛，不会跑到屏幕外。
 */
class QuickPanel(
    private val context: Context,
    private val onAction: (Action) -> Unit
) {
    enum class Action {
        TOGGLE_MIC, TOGGLE_CAPTURE,
        PICK_BG, CLEAR_BG,
        TOGGLE_LARGE, TOGGLE_SMALL,
        RESET, SETTINGS, CLOSE
    }

    private val density = context.resources.displayMetrics.density

    private val accent = 0xFF7FE0A8.toInt()
    private val dim = 0xFF8FA396.toInt()
    private val panelColor = 0xF2101613.toInt()

    val root: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = false
        background = GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius = 14f * density
            setStroke((1f * density).toInt(), 0x667FE0A8)
        }
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val micItem: TextView
    private val captureItem: TextView
    private val bgLabel: TextView

    val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        root.addView(content)
        micItem = addItem("麦克风") { onAction(Action.TOGGLE_MIC) }
        captureItem = addItem("机内音频") { onAction(Action.TOGGLE_CAPTURE) }
        bgLabel = addItem("设置背景") { onAction(Action.PICK_BG) }
        addItem("清除背景") { onAction(Action.CLEAR_BG) }
        addItem("桌面大窗 开/关") { onAction(Action.TOGGLE_LARGE) }
        addItem("悬浮小窗 开/关") { onAction(Action.TOGGLE_SMALL) }
        addItem("重置动画") { onAction(Action.RESET) }
        addItem("设置") { onAction(Action.SETTINGS) }
        addItem("关闭悬浮窗") { onAction(Action.CLOSE) }
    }

    private fun addItem(label: String, click: () -> Unit): TextView {
        val view = TextView(context).apply {
            text = label
            setTextColor(dim)
            textSize = 13f
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { click() }
            isClickable = true
        }
        content.addView(view)
        return view
    }

    fun setBgState(hasBackground: Boolean) {
        bgLabel.text = if (hasBackground) "更换背景" else "设置背景"
    }

    /** 高亮当前正在运行的音频源。 */
    fun setSourceState(kind: SourceKind) {
        micItem.setTextColor(if (kind == SourceKind.MIC) accent else dim)
        captureItem.setTextColor(if (kind == SourceKind.CAPTURE) accent else dim)
        micItem.text = if (kind == SourceKind.MIC) "麦克风 · 运行中" else "麦克风"
        captureItem.text = if (kind == SourceKind.CAPTURE) "机内音频 · 运行中" else "机内音频"
    }

    /**
     * 在指定矩形右侧弹出；超出屏幕时自动翻到左侧或向内收。
     * 视图尚未 attach 时（第一次显示）先按估算尺寸放置，attach 后再校正一次。
     */
    fun show(x: Int, y: Int, w: Int, h: Int, windowManager: WindowManager) {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val panelW = if (root.width > 0) root.width else (170 * density).toInt()
        val panelH = if (root.height > 0) root.height else (340 * density).toInt()

        var targetX = x + w + dp(8)
        if (targetX + panelW > screenW - dp(8)) targetX = x - panelW - dp(8)
        if (targetX < dp(8)) targetX = dp(8)

        var targetY = y
        if (targetY + panelH > screenH - dp(8)) targetY = screenH - panelH - dp(8)
        if (targetY < dp(8)) targetY = dp(8)

        lp.x = targetX
        lp.y = targetY

        root.visibility = View.VISIBLE
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }

        // attach 后再校正一次，避免第一次估算尺寸不准
        root.post {
            val realW = root.width
            val realH = root.height
            if (realW > 0 && realH > 0) {
                var nx = x + w + dp(8)
                if (nx + realW > screenW - dp(8)) nx = x - realW - dp(8)
                if (nx < dp(8)) nx = dp(8)
                var ny = y
                if (ny + realH > screenH - dp(8)) ny = screenH - realH - dp(8)
                if (ny < dp(8)) ny = dp(8)
                if (nx != lp.x || ny != lp.y) {
                    lp.x = nx
                    lp.y = ny
                    try {
                        windowManager.updateViewLayout(root, lp)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun hide() {
        root.visibility = View.GONE
    }

    fun destroy(windowManager: WindowManager) {
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}

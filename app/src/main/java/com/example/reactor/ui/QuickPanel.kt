package com.example.reactor.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

    val root: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = false
        background = GradientDrawable().apply {
            setColor(0xE6101613.toInt())
            cornerRadius = 14f * context.resources.displayMetrics.density
            setStroke((1f * context.resources.displayMetrics.density).toInt(), 0x804DFFA6.toInt())
        }
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    val view: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bgLabel: TextView

    val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        root.addView(view)
        addItem("🎤  麦克风") { onAction(Action.TOGGLE_MIC) }
        addItem("🔊  机内音频") { onAction(Action.TOGGLE_CAPTURE) }
        bgLabel = addItem("🖼  设置背景") { onAction(Action.PICK_BG) }
        addItem("⬜  清除背景") { onAction(Action.CLEAR_BG) }
        addItem("📺  桌面层 开/关") { onAction(Action.TOGGLE_LARGE) }
        addItem("🔲  悬浮层 开/关") { onAction(Action.TOGGLE_SMALL) }
        addItem("↺  重置") { onAction(Action.RESET) }
        addItem("⚙  设置") { onAction(Action.SETTINGS) }
        addItem("✕  关闭") { onAction(Action.CLOSE) }
    }

    private fun addItem(label: String, click: () -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            setTextColor(0xFF4DFFA6.toInt())
            textSize = 13f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { click() }
        }
        view.addView(tv)
        return tv
    }

    fun setBgState(has: Boolean) { bgLabel.text = if (has) "🖼  更换背景" else "🖼  设置背景" }

    fun show(x: Int, y: Int, w: Int, h: Int) {
        root.visibility = View.VISIBLE
        lp.x = x + w + dp(6)
        lp.y = y
    }

    fun hide() { root.visibility = View.GONE }
    fun destroy(wm: WindowManager) { try { wm.removeView(root) } catch (_: Exception) {} }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
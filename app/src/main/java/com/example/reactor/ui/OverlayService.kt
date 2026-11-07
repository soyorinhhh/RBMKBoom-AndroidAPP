package com.example.reactor.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.reactor.MainActivity
import com.example.reactor.R
import com.example.reactor.audio.AudioEngine
import com.example.reactor.audio.AudioStatus
import com.example.reactor.audio.SourceKind
import com.example.reactor.config.ReactorConfig
import com.example.reactor.util.AppLog
import com.example.reactor.util.UsageAccess
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 悬浮窗前台服务。
 *
 * 相比初版修掉的核心问题：
 * 1. **前台服务类型**：初版在 API 29+ 无条件传 FOREGROUND_SERVICE_TYPE_SPECIAL_USE，
 *    而这个常量是 API 34 才有的 —— 在 Android 12 上 startForeground 会直接抛
 *    IllegalArgumentException，服务刚建就崩。现在按版本分级处理；
 * 2. API 26–28 初版根本没有调用过 startForeground（只有 >= Q 的分支），
 *    用 startForegroundService 启动会在 5 秒后抛 ANR 级异常；现在所有版本都会调用；
 * 3. 麦克风需要先升级前台服务类型再采集，且 Android 12 不允许在后台这样做，
 *    失败时引导用户从 App 内开启，而不是静默失败；
 * 4. MediaProjection 必须先升级 mediaProjection 类型再 getMediaProjection，
 *    并注册 Callback，投影停止时立即释放采集；
 * 5. 所有音频资源统一由 AudioEngine 管理，切换模式不会出现两个 AudioRecord 并存。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "ReactorOverlay"
        private const val CHANNEL_ID = "reactor_overlay"
        private const val NOTI_ID = 1

        /** 连续检测到 Launcher 几次才显示大窗，用于压制「闪现」。 */
        private const val LAUNCHER_STREAK_REQUIRED = 2

        const val ACTION_CAPTURE_READY = "com.example.reactor.CAPTURE_READY"
        const val ACTION_REFRESH_BG = "com.example.reactor.REFRESH_BG"
        const val ACTION_TOGGLE_MIC = "com.example.reactor.TOGGLE_MIC"
        const val ACTION_STOP_MIC = "com.example.reactor.STOP_MIC"
        const val ACTION_TO_SYNTH = "com.example.reactor.TO_SYNTH"
        const val ACTION_REFRESH_CONFIG = "com.example.reactor.REFRESH_CONFIG"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_DISABLE_LARGE = "disableLarge"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var panel: QuickPanel

    private lateinit var smallRoot: FrameLayout
    private lateinit var smallReactor: ReactorView
    private lateinit var smallParams: WindowManager.LayoutParams

    private lateinit var largeRoot: FrameLayout
    private lateinit var largeReactor: ReactorView
    private lateinit var largeParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private val engine = AudioEngine(bandCount = 48)
    private val frameBuffer = FloatArray(48)

    private var config = ReactorConfig()
    private var lastFrameMs = 0L
    private var frameScheduled = false
    private var watchdogCountdown = 0

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var foregroundType = 0

    private var launcherPackage: String? = null
    private var onLauncher = false
    private var launcherStreak = 0
    private var lastVisibilityCheck = 0L
    private var largeEnabled = true
    private var smallEnabled = true
    private var usagePermissionWarned = false
    private var screenOn = true

    private var backgroundToken = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var downMs = 0L
    private var dragging = false
    private var longFired = false
    private val touchSlop = 14f
    private val longPressRunnable = Runnable {
        longFired = true
        showPanel()
    }

    private val configListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { applyConfig() }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    // 息屏后没必要继续跑渲染；采集也停掉，避免长期占用麦克风/投影
                    stopFrameLoop()
                    if (engine.currentMode() != SourceKind.SYNTH) engine.switchTo(SourceKind.SYNTH)
                    releaseProjection()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    startFrameLoop()
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    startFrameLoop()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppLog.add("服务", "onCreate start，SDL=${Build.VERSION.SDK_INT}")
        startAsForeground()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        config = ReactorConfig.load(this)
        engine.adaptiveGain = config.adaptiveGain
        engine.sensitivity = config.sensitivity
        engine.onSourceDied = { reason ->
            handler.post {
                toast("音频中断：$reason，已切回演示信号")
                engine.switchTo(SourceKind.SYNTH)
                refreshForegroundType()
                updateNotification()
            }
        }

        resolveLauncherPackage()
        buildLargeLayer()
        buildSmallLayer()
        panel = QuickPanel(this) { action -> handlePanelAction(action) }

        val largeAdded = addWindowSafely(largeRoot, largeParams, "大窗")
        val smallAdded = addWindowSafely(smallRoot, smallParams, "小窗")
        // 面板也必须在这里加进 WindowManager。
        // 之前重写时漏了这一行，面板只能等看门狗巡检时被补上（实测晚了 1.4 秒），
        // 真机上表现就是「长按呼不出面板 / 面板一闪就没」。
        addWindowSafely(panel.root, panel.lp, "面板")
        if (!smallAdded && !largeAdded) {
            // 悬浮窗权限没了，直接收摊，别留一个看不见的服务
            toast("没有悬浮窗权限，已停止")
            stopSelf()
            return
        }

        applyConfigToViews()
        getSharedPreferences(ReactorConfig.PREF, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(configListener)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        // 默认用演示信号，无需任何权限就能看到效果
        engine.switchTo(SourceKind.SYNTH)

        handler.post { applyBackground() }
        updateLayerVisibility(force = true)
        startFrameLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_CAPTURE_READY -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null && resultCode == android.app.Activity.RESULT_OK) {
                    startCapture(resultCode, data)
                } else {
                    toast("录屏授权被取消")
                }
            }
            ACTION_REFRESH_BG -> applyBackground()
            ACTION_TOGGLE_MIC -> enableMic()
            ACTION_STOP_MIC -> {
                if (engine.currentMode() == SourceKind.MIC) switchToSynth()
            }
            ACTION_TO_SYNTH -> switchToSynth()
            ACTION_REFRESH_CONFIG -> applyConfig()
            else -> {
                if (intent.getBooleanExtra(EXTRA_DISABLE_LARGE, false)) {
                    largeEnabled = false
                    updateLayerVisibility(force = true)
                }
            }
        }
    }

    // ==================== 前台服务 ====================

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "控制棒悬浮窗", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        enableVibration(false)
                    }
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TO_SYNTH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reactor)
            .setContentTitle("控制棒")
            .setContentText(engine.currentMode().cn + " · 悬浮窗运行中")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "切回演示", stop)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTI_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    /**
     * 计算当前应该声明的前台服务类型。
     *
     * - API 26–28：没有类型概念，走两参 startForeground；
     * - API 29–33：只有确实需要时才带 microphone / mediaProjection；
     *   **绝不能带 SPECIAL_USE**（34 才有，31 上传了直接崩）；
     * - API 34+：必须带类型，用 specialUse 托底。
     */
    private fun computeForegroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (engine.currentMode() == SourceKind.MIC) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (projection != null) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return type
    }

    private fun startAsForeground() {
        try {
            val type = computeForegroundType()
            if (type != 0) {
                startForeground(NOTI_ID, buildNotification(), type)
                foregroundType = type
            } else {
                startForeground(NOTI_ID, buildNotification())
                foregroundType = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败，退回不带类型", e)
            try {
                startForeground(NOTI_ID, buildNotification())
                foregroundType = 0
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground 兜底也失败", e2)
            }
        }
    }

    /**
     * 按当前模式与投影状态重新声明前台服务类型。
     * @return true 表示类型已经带上（或被系统接受）
     */
    private fun refreshForegroundType(): Boolean {
        return try {
            val type = computeForegroundType()
            if (type != 0) {
                startForeground(NOTI_ID, buildNotification(), type)
                foregroundType = type
            } else {
                startForeground(NOTI_ID, buildNotification())
                foregroundType = 0
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "升级前台服务类型被拒绝（多半是后台限制）", e)
            try {
                startForeground(NOTI_ID, buildNotification())
                foregroundType = 0
            } catch (_: Exception) {
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "刷新前台服务类型失败", e)
            false
        }
    }

    // ==================== 悬浮窗 ====================

    private fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun buildLargeLayer() {
        largeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 大窗整体略透明，桌面穿透感更强
            alpha = 0.92f
        }

        largeRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            // 关键：一开始就是 GONE。首版加窗时默认 VISIBLE，要等第一次可见性检测
            // （最多 800ms 后）才隐藏，于是会在非桌面场景闪一下、把桌面挡一瞬间。
            visibility = View.GONE
        }
        largeReactor = ReactorView(this)
        largeRoot.addView(
            largeReactor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** 屏幕尺寸：优先 currentWindowMetrics，旋转后它是准的（displayMetrics 可能是旧方向）。 */
    private fun screenSize(): IntArray {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
                intArrayOf(bounds.width(), bounds.height())
            } else {
                val metrics = resources.displayMetrics
                intArrayOf(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (e: Exception) {
            val metrics = resources.displayMetrics
            AppLog.add("配置", "读取屏幕尺寸失败，退回 displayMetrics：${e.javaClass.simpleName}")
            intArrayOf(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun buildSmallLayer() {
        val size = (config.smallSizeDp * resources.displayMetrics.density).toInt()
        val screen = screenSize()
        smallParams = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screen[0] - size) / 2
            y = screen[1] / 4
        }
        smallRoot = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        smallReactor = ReactorView(this)
        smallRoot.addView(
            smallReactor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        smallRoot.setOnTouchListener { _, event -> handleTouch(event) }
    }

    private fun addWindowSafely(view: View, params: WindowManager.LayoutParams, name: String): Boolean {
        return try {
            windowManager.addView(view, params)
            AppLog.add("窗口", "$name 已添加 ${params.width}x${params.height}")
            true
        } catch (e: Exception) {
            AppLog.add("窗口", "$name 添加失败：${e.javaClass.simpleName} ${e.message ?: ""}")
            Log.e(TAG, "$name 添加失败", e)
            false
        }
    }

    private fun resolveLauncherPackage() {
        launcherPackage = try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private fun hasUsagePermission(): Boolean = UsageAccess.has(this)

    private fun isLauncherForeground(): Boolean =
        launcherPackage?.let { pkg ->
            val usm = getSystemService(UsageStatsManager::class.java) ?: return@let false
            try {
                val end = System.currentTimeMillis()
                val events = usm.queryEvents(end - 2500, end)
                var last = ""
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                    ) {
                        last = event.packageName
                    }
                }
                last.isNotEmpty() && last == pkg
            } catch (_: Exception) {
                false
            }
        } ?: false

    private fun updateLayerVisibility(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastVisibilityCheck < 800L) return
        lastVisibilityCheck = now

        val canDetect = hasUsagePermission()
        if (!canDetect && !usagePermissionWarned) {
            usagePermissionWarned = true
        }
        val launcherVisible = canDetect && screenOn && isLauncherForeground()

        // 防抖：启动器切换动画、ColorOS 的事件延迟都会让单次检测误判成「在桌面」，
        // 大窗就会闪一下盖住别的应用。连续两次都判定在桌面才真正显示；离开立刻隐藏。
        if (launcherVisible) {
            if (launcherStreak < LAUNCHER_STREAK_REQUIRED) launcherStreak++
        } else {
            launcherStreak = 0
        }
        val effective = launcherVisible && launcherStreak >= LAUNCHER_STREAK_REQUIRED

        if (!force && effective == onLauncher) {
            applyVisibility(effective)
            return
        }
        onLauncher = effective
        applyVisibility(effective)
    }

    private fun applyVisibility(launcherVisible: Boolean) {
        if (!::largeRoot.isInitialized || !::smallRoot.isInitialized) return
        val largeVisible = largeEnabled && launcherVisible
        largeRoot.visibility = if (largeVisible) View.VISIBLE else View.GONE
        smallRoot.visibility = if (smallEnabled) View.VISIBLE else View.GONE
        smallReactor.visibility = if (smallEnabled) View.VISIBLE else View.GONE
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = smallParams.x
                startY = smallParams.y
                downMs = SystemClock.uptimeMillis()
                dragging = false
                longFired = false
                handler.postDelayed(longPressRunnable, 460L)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    smallParams.x = (startX + dx).toInt()
                    smallParams.y = (startY + dy).toInt()
                    safeUpdateLayout(smallRoot, smallParams)
                    panel.hide()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (!dragging && !longFired && SystemClock.uptimeMillis() - downMs < 240L) {
                    smallReactor.triggerFlash()
                }
                if (dragging) snapToEdge()
            }
        }
        return true
    }

    private fun safeUpdateLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "更新悬浮窗位置失败", e)
        }
    }

    private fun snapToEdge() {
        val screenWidth = screenSize()[0]
        val margin = (8 * resources.displayMetrics.density).toInt()
        smallParams.x = if (smallParams.x + smallParams.width / 2 < screenWidth / 2) {
            margin
        } else {
            screenWidth - smallParams.width - margin
        }
        safeUpdateLayout(smallRoot, smallParams)
    }

    private fun showPanel() {
        // 再长按一次收起，避免面板一直挂在屏幕上
        if (panel.isShowing()) {
            panel.hide()
            return
        }
        vibrate(18)
        AppLog.add("面板", "长按呼出，当前音源=${engine.currentMode().cn}")
        panel.setBgState(BackgroundManager.getUri(this) != null)
        panel.setSourceState(engine.currentMode())
        panel.show(smallParams.x, smallParams.y, smallParams.width, smallParams.height, windowManager)
    }

    // ==================== 交互 ====================

    private fun handlePanelAction(action: QuickPanel.Action) {
        AppLog.add("面板", "点击 $action")
        var closeAfter = true
        when (action) {
            QuickPanel.Action.TOGGLE_MIC -> enableMic()
            QuickPanel.Action.TOGGLE_CAPTURE -> toggleCapture()
            QuickPanel.Action.PICK_BG -> closeAfter = openMain("pick_background")
            QuickPanel.Action.CLEAR_BG -> {
                BackgroundManager.setUri(this, null)
                BackgroundManager.setEnabled(this, false)
                smallReactor.setBackgroundBitmap(null)
                vibrate(15)
            }
            QuickPanel.Action.TOGGLE_LARGE -> {
                largeEnabled = !largeEnabled
                updateLayerVisibility(force = true)
                vibrate(15)
            }
            QuickPanel.Action.TOGGLE_SMALL -> {
                smallEnabled = !smallEnabled
                updateLayerVisibility(force = true)
                vibrate(15)
            }
            QuickPanel.Action.RESET -> {
                smallReactor.reset()
                largeReactor.reset()
            }
            QuickPanel.Action.SETTINGS -> closeAfter = openMain("settings")
            QuickPanel.Action.CLOSE -> {
                AppLog.add("面板", "用户主动退出悬浮窗")
                stopSelf()
            }
        }
        // 打开界面的动作如果被系统拦截，面板保持显示，避免「点了就没反应、选项还全没了」
        if (closeAfter) panel.hide()
    }

    /**
     * 从悬浮窗拉起界面。
     *
     * Android 10+ 限制后台启动 Activity，国产 ROM 更严格。
     * 首版直接 startActivity 且失败也只是吞异常，用户看到的就是「点了设置，面板消失，什么也没打开」。
     * 这里返回是否成功，失败时提示用户手动打开。
     */
    private fun openMain(action: String): Boolean {
        return try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("action", action)
                }
            )
            AppLog.add("交互", "打开主界面：$action")
            true
        } catch (e: Exception) {
            AppLog.add("交互", "打开主界面失败：${e.javaClass.simpleName} ${e.message ?: ""}")
            toast("系统拦截了后台启动，请手动打开「控制棒」")
            false
        }
    }

    private fun toggleCapture() {
        when {
            engine.currentMode() == SourceKind.CAPTURE -> switchToSynth()
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> toast("系统版本低于 Android 10，不支持机内捕获")
            else -> openMain("request_capture")
        }
    }

    private fun enableMic() {
        if (engine.currentMode() == SourceKind.MIC) {
            switchToSynth()
            return
        }
        if (!hasMicPermission()) {
            toast("需要麦克风权限，请从 App 内开启")
            openMain("request_mic")
            return
        }

        // Android 12+ 不允许在后台把前台服务升级成 microphone 类型：
        // 先尝试声明类型，失败就说明当前处于后台限制下。
        var promoted = true
        try {
            val type = computeForegroundType() or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTI_ID, buildNotification(), type)
            foregroundType = type
        } catch (e: SecurityException) {
            Log.w(TAG, "麦克风前台服务类型被系统拒绝", e)
            promoted = false
        } catch (e: Exception) {
            Log.w(TAG, "声明麦克风前台服务类型失败", e)
            promoted = false
        }

        val ok = engine.switchTo(SourceKind.MIC)
        if (!ok) {
            val reason = AudioStatus.snapshot?.lastError ?: "未知原因"
            toast("麦克风启动失败：$reason")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !promoted) {
                toast("Android 12 限制后台启用麦克风，请从 App 内点击开启")
                openMain("request_mic")
            }
            switchToSynth(keepToast = true)
            return
        }
        refreshForegroundType()
        updateNotification()
        toast("麦克风已启用")
    }

    private fun switchToSynth(keepToast: Boolean = false) {
        engine.switchTo(SourceKind.SYNTH)
        releaseProjection()
        refreshForegroundType()
        updateNotification()
        if (!keepToast) toast("已切回演示信号")
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("系统版本过低，不支持机内音频")
            return
        }
        // 必须先声明 mediaProjection 类型，再取 projector，否则 Android 14 会抛异常、
        // Android 12 也会因为类型不匹配而失败
        releaseProjection()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection 失败", e)
            toast("获取录屏授权失败：${e.javaClass.simpleName}")
            return
        }
        if (mediaProjection == null) {
            toast("获取录屏授权失败")
            return
        }

        projection = mediaProjection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    Log.i(TAG, "MediaProjection 已停止")
                    if (projection === mediaProjection) {
                        engine.switchTo(SourceKind.SYNTH)
                        releaseProjection()
                        refreshForegroundType()
                        updateNotification()
                        toast("录屏授权已结束，已切回演示信号")
                    }
                }
            }
        }
        projectionCallback = callback
        try {
            mediaProjection.registerCallback(callback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "注册投影回调失败", e)
        }

        if (!refreshForegroundType()) {
            toast("无法升级前台服务类型，机内音频可能不可用")
        }

        val ok = engine.switchTo(SourceKind.CAPTURE, mediaProjection)
        if (!ok) {
            val reason = AudioStatus.snapshot?.lastError ?: "未知原因"
            toast("机内音频启动失败：$reason")
            releaseProjection()
            refreshForegroundType()
            return
        }
        updateNotification()
        vibrate(15)
        toast("机内音频已启用")
    }

    private fun releaseProjection() {
        val cb = projectionCallback
        val p = projection
        projectionCallback = null
        projection = null
        if (cb != null && p != null) {
            try {
                p.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        try {
            p?.stop()
        } catch (_: Exception) {
        }
    }

    // ==================== 渲染循环 ====================

    private val frameTick = object : Runnable {
        override fun run() {
            if (!frameScheduled) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrameMs == 0L) 1f / config.fps else (now - lastFrameMs) / 1000f
            lastFrameMs = now

            engine.snapshot(frameBuffer)

            var needsLayoutSync = false
            if (watchdogCountdown <= 0) {
                watchdogCountdown = config.fps * 2      // 约每 2 秒体检一次
                needsLayoutSync = ensureWindowsAttached()
            } else {
                watchdogCountdown--
            }

            val largeVisible = largeRoot.visibility == View.VISIBLE
            val smallVisible = smallRoot.visibility == View.VISIBLE
            if (largeVisible) largeReactor.update(frameBuffer, dt)
            if (smallVisible) smallReactor.update(frameBuffer, dt)

            updateLayerVisibility(force = needsLayoutSync)

            handler.postDelayed(this, (1000L / config.fps).coerceAtLeast(16L))
        }
    }

    /**
     * 看门狗：ColorOS 在旋转、切后台、权限变动时可能把 overlay 窗口摘掉，
     * 而服务本身还活着 —— 表现就是「横屏后消失」。
     * 这里定期确认窗口还挂在 WindowManager 上，掉了就重新加回去。
     */
    private fun ensureWindowsAttached(): Boolean {
        var changed = false
        if (::smallRoot.isInitialized && smallEnabled && !smallRoot.isAttachedToWindow) {
            AppLog.add("窗口", "小窗不见了，重新添加")
            if (addWindowSafely(smallRoot, smallParams, "小窗")) changed = true
        }
        if (::largeRoot.isInitialized && !largeRoot.isAttachedToWindow) {
            AppLog.add("窗口", "大窗不见了，重新添加")
            if (addWindowSafely(largeRoot, largeParams, "大窗")) changed = true
        }
        if (::panel.isInitialized && panel.root.isAttachedToWindow.not() && panel.isShowing()) {
            AppLog.add("窗口", "面板不见了，重新添加")
            addWindowSafely(panel.root, panel.lp, "面板")
        }
        return changed
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLog.add("配置", "旋转/尺寸变化 → ${newConfig.orientation}")
        handler.post { applyOrientation() }
    }

    /**
     * 横竖屏切换后按新屏幕尺寸重排窗口。
     * overlay 窗口用的是绝对像素坐标，不重排就会出现「横屏后跑到屏幕外」。
     */
    private fun applyOrientation() {
        if (!::smallRoot.isInitialized || !::largeRoot.isInitialized) return
        val density = resources.displayMetrics.density
        val screen = screenSize()
        val width = screen[0]
        val height = screen[1]
        val size = (config.smallSizeDp * density).toInt()

        val oldX = smallParams.x
        val oldY = smallParams.y
        val wasLeft = oldX + smallParams.width / 2 < width / 2

        smallParams.width = size
        smallParams.height = size
        smallParams.x = if (wasLeft) (8 * density).toInt() else width - size - (8 * density).toInt()
        smallParams.y = oldY.coerceIn(0, (height - size).coerceAtLeast(0))
        safeUpdateLayout(smallRoot, smallParams)

        // 大窗是 MATCH_PARENT，尺寸由系统给，这里只需要让它重画几何
        largeReactor.reset()
        smallReactor.reset()
        lastVisibilityCheck = 0
        updateLayerVisibility(force = true)
        AppLog.add("配置", "已按 ${width}x${height} 重排，小窗(${smallParams.x},${smallParams.y})")
    }

    private fun startFrameLoop() {
        if (frameScheduled || !screenOn) return
        frameScheduled = true
        lastFrameMs = 0L
        handler.postDelayed(frameTick, (1000L / config.fps).coerceAtLeast(16L))
    }

    private fun stopFrameLoop() {
        frameScheduled = false
        handler.removeCallbacks(frameTick)
        lastFrameMs = 0L
    }

    private fun restartFrameLoop() {
        stopFrameLoop()
        startFrameLoop()
    }

    // ==================== 配置 / 背景 ====================

    private fun applyConfigToViews() {
        smallReactor.applyConfig(config)
        // 大窗用稀疏一档，全屏下模块太密会糊成一片
        largeReactor.applyConfig(
            config.copy(
                rodDensity = if (config.rodDensity == ReactorConfig.RodDensity.DENSE) {
                    ReactorConfig.RodDensity.NORMAL
                } else {
                    ReactorConfig.RodDensity.SPARSE
                }
            )
        )
    }

    /**
     * 按设置实时调整悬浮小窗尺寸。
     * 以小窗中心为锚点缩放，改尺寸后不会莫名跳到屏幕另一边。
     */
    private fun applySmallWindowSize() {
        if (!::smallRoot.isInitialized || !::smallParams.isInitialized) return
        val size = (config.smallSizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (smallParams.width == size && smallParams.height == size) return

        val screen = screenSize()
        val centerX = smallParams.x + smallParams.width / 2
        val centerY = smallParams.y + smallParams.height / 2
        smallParams.width = size
        smallParams.height = size
        smallParams.x = (centerX - size / 2).coerceIn(0, (screen[0] - size).coerceAtLeast(0))
        smallParams.y = (centerY - size / 2).coerceIn(0, (screen[1] - size).coerceAtLeast(0))

        if (smallRoot.isAttachedToWindow) safeUpdateLayout(smallRoot, smallParams)
        smallReactor.reset()
        // 背景图是按小窗尺寸裁的，尺寸变了必须重裁，否则会被拉花
        applyBackground()
        AppLog.add("窗口", "小窗尺寸 → ${config.smallSizeDp}dp (${size}px)")
    }

    private fun applyConfig() {
        config = ReactorConfig.load(this)
        engine.adaptiveGain = config.adaptiveGain
        engine.sensitivity = config.sensitivity
        applyConfigToViews()
        applySmallWindowSize()
        restartFrameLoop()
    }

    private fun applyBackground() {
        if (!BackgroundManager.isEnabled(this)) {
            smallReactor.setBackgroundBitmap(null)
            return
        }
        val uri = BackgroundManager.getUri(this)
        if (uri == null) {
            smallReactor.setBackgroundBitmap(null)
            return
        }
        val token = ++backgroundToken
        val targetW = smallParams.width
        val targetH = smallParams.height
        thread(name = "bg-decode", isDaemon = true) {
            val bitmap = BackgroundManager.decode(this, uri, targetW, targetH)
            handler.post {
                if (token == backgroundToken) smallReactor.setBackgroundBitmap(bitmap)
            }
        }
    }

    // ==================== 杂项 ====================

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun vibrate(ms: Long) {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        AppLog.add("服务", "onDestroy，音源=${engine.currentMode().cn}")
        isRunning = false
        stopFrameLoop()
        handler.removeCallbacksAndMessages(null)

        engine.onSourceDied = null
        engine.stop()
        releaseProjection()

        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        try {
            getSharedPreferences(ReactorConfig.PREF, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(configListener)
        } catch (_: Exception) {
        }

        if (::panel.isInitialized) panel.destroy(windowManager)
        if (::largeRoot.isInitialized) {
            try {
                windowManager.removeView(largeRoot)
            } catch (_: Exception) {
            }
            largeReactor.setBackgroundBitmap(null)
        }
        if (::smallRoot.isInitialized) {
            try {
                windowManager.removeView(smallRoot)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}

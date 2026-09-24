package com.example.reactor.ui

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.reactor.MainActivity
import com.example.reactor.audio.CaptureSource
import com.example.reactor.audio.MicSource
import com.example.reactor.audio.SynthSource
import com.example.reactor.config.ReactorConfig
import kotlin.concurrent.thread
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "reactor_overlay"
        private const val NOTI_ID = 1
        const val ACTION_CAPTURE_READY = "com.example.reactor.CAPTURE_READY"
        const val ACTION_REFRESH_BG = "com.example.reactor.REFRESH_BG"
    }

    enum class SourceKind { SYNTH, MIC, CAPTURE }

    private lateinit var wm: WindowManager
    private lateinit var panel: QuickPanel
    private lateinit var noti: Notification

    private lateinit var smallRoot: FrameLayout
    private lateinit var smallReactor: ReactorView
    private lateinit var smallParams: WindowManager.LayoutParams

    private lateinit var largeRoot: FrameLayout
    private lateinit var largeReactor: ReactorView
    private lateinit var largeParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private val synth = SynthSource(bandCount = 48, loopSeconds = 8f)
    private val mic = MicSource(bandCount = 48)
    private var captureSource: CaptureSource? = null
    private var projectionRef: MediaProjection? = null

    private var currentSource = SourceKind.SYNTH
    private val frameBuf = FloatArray(48)
    private var t = 0f
    private var lastMs = 0L
    private var config = ReactorConfig()

    private var launcherPkg: String? = null
    private var onLauncher = false
    private var lastVisibilityCheck = 0L
    private var largeEnabled = true
    private var smallEnabled = true

    private var bgDecodeToken = 0
    private var silentFrames = 0

    private var downRawX = 0f; private var downRawY = 0f
    private var startX = 0; private var startY = 0
    private var downMs = 0L
    private var dragging = false
    private var longFired = false
    private val slop = 14f
    private val longPressRunnable = Runnable { longFired = true; showPanel() }

    private val configListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> handler.post { applyConfig() } }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) {
                mic.stop()
                captureSource?.stop(); captureSource = null
                projectionRef?.stop(); projectionRef = null
                if (currentSource != SourceKind.SYNTH) currentSource = SourceKind.SYNTH
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        largeEnabled = !(intent?.getBooleanExtra("disableLarge", false) ?: false)
        startForegroundCompat()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        config = ReactorConfig.load(this)

        initLauncherPkg()
        buildLargeLayer()
        buildSmallLayer()

        panel = QuickPanel(this) { action -> onPanelAction(action) }

        wm.addView(largeRoot, largeParams)
        wm.addView(smallRoot, smallParams)
        wm.addView(panel.root, panel.lp)

        largeReactor.applyConfig(config.copy(rodDensity = ReactorConfig.RodDensity.SPARSE))
        smallReactor.applyConfig(config)
        mic.setAdaptiveGain(config.adaptiveGain)

        getSharedPreferences(ReactorConfig.PREF, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(configListener)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        handler.post { applyBackground() }
        handler.postDelayed(frameTick, 1000L / config.fps)
        updateLayerVisibility(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE_READY -> {
                val rc = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")
                if (rc == Activity.RESULT_OK && data != null) startCapture(rc, data)
            }
            ACTION_REFRESH_BG -> applyBackground()
        }
        return START_STICKY
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun buildLargeLayer() {
        largeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        largeRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.55f
            isClickable = false
            isFocusable = false
        }
        val top = systemBarHeight("status_bar_height")
        val bottom = systemBarHeight("navigation_bar_height")
        largeRoot.setPadding(0, top, 0, bottom)

        largeReactor = ReactorView(this)
        largeRoot.addView(largeReactor, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun buildSmallLayer() {
        val size = (150 * resources.displayMetrics.density).toInt()
        smallParams = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 80; y = 300 }
        smallRoot = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        smallReactor = ReactorView(this)
        smallRoot.addView(smallReactor, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        smallRoot.setOnTouchListener { _, e -> handleTouch(e) }
    }

    private fun systemBarHeight(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun initLauncherPkg() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        launcherPkg = packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    private val frameTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastMs == 0L) 1f / config.fps else (now - lastMs) / 1000f
            lastMs = now

            updateLayerVisibility()

            when (currentSource) {
                SourceKind.SYNTH -> { t = (t + dt) % 8f; synth.read(t).copyInto(frameBuf) }
                SourceKind.MIC -> mic.read(frameBuf)
                SourceKind.CAPTURE -> captureSource?.read(frameBuf) ?: frameBuf.fill(0f)
            }

            if (currentSource == SourceKind.CAPTURE) {
                var silent = true
                for (v in frameBuf) if (v > 0.02f) { silent = false; break }
                if (silent) { if (++silentFrames == 75) toast("检测不到音频，换个播放源或切麦克风") } else silentFrames = 0
            }

            val sens = config.sensitivity
            if (sens != 1f) { for (i in frameBuf.indices) frameBuf[i] = (frameBuf[i] * sens).coerceIn(0f, 1f) }

            if (largeRoot.visibility == View.VISIBLE) largeReactor.update(frameBuf, dt)
            if (smallRoot.visibility == View.VISIBLE) smallReactor.update(frameBuf, dt)

            handler.postDelayed(this, (1000L / config.fps).coerceAtLeast(1L))
        }
    }

    private fun checkLauncherForeground(): Boolean {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 2500, end)
        var last = ""
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        return last.isNotEmpty() && last == launcherPkg
    }

    private fun hasUsagePermission(): Boolean {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 1000, end)
        return !stats.isNullOrEmpty()
    }

    private fun updateLayerVisibility(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastVisibilityCheck < 1000L) return
        lastVisibilityCheck = now

        val launcher = if (hasUsagePermission()) checkLauncherForeground() else false
        if (launcher == onLauncher && !force) return
        onLauncher = launcher

        largeRoot.visibility = if (largeEnabled && launcher) View.VISIBLE else View.GONE
        smallRoot.visibility = if (smallEnabled) View.VISIBLE else View.GONE
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                startX = smallParams.x; startY = smallParams.y
                downMs = SystemClock.uptimeMillis()
                dragging = false; longFired = false
                handler.postDelayed(longPressRunnable, 480L)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; handler.removeCallbacks(longPressRunnable) }
                if (dragging) {
                    smallParams.x = (startX + dx).toInt()
                    smallParams.y = (startY + dy).toInt()
                    wm.updateViewLayout(smallRoot, smallParams)
                    panel.hide()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (!dragging && !longFired && SystemClock.uptimeMillis() - downMs < 250L) smallReactor.reset()
                snapToEdge()
            }
        }
        return true
    }

    private fun snapToEdge() {
        val screenW = resources.displayMetrics.widthPixels
        smallParams.x = if (smallParams.x + smallParams.width / 2 < screenW / 2) 0 else screenW - smallParams.width
        wm.updateViewLayout(smallRoot, smallParams)
    }

    private fun showPanel() {
        vibrate(20)
        panel.setBgState(BackgroundManager.getUri(this) != null)
        panel.show(smallParams.x, smallParams.y, smallParams.width, smallParams.height)
    }

    private fun onPanelAction(action: QuickPanel.Action) {
        when (action) {
            QuickPanel.Action.TOGGLE_MIC -> {
                if (currentSource == SourceKind.MIC) { mic.stop(); currentSource = SourceKind.SYNTH; vibrate(15) }
                else if (hasMicPermission() && mic.start()) {
                    captureSource?.stop(); captureSource = null; projectionRef?.stop(); projectionRef = null
                    mic.setAdaptiveGain(config.adaptiveGain); currentSource = SourceKind.MIC; vibrate(15)
                } else toast("无法启动麦克风，请检查权限")
            }
            QuickPanel.Action.TOGGLE_CAPTURE -> {
                when {
                    currentSource == SourceKind.CAPTURE -> { captureSource?.stop(); captureSource = null; projectionRef?.stop(); projectionRef = null; currentSource = SourceKind.SYNTH; vibrate(15) }
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> toast("系统版本过低，不支持机内捕获")
                    else -> {
                        val i = Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra("action", "request_capture") }
                        startActivity(i)
                    }
                }
            }
            QuickPanel.Action.PICK_BG -> {
                val i = Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra("action", "pick_background") }
                startActivity(i)
            }
            QuickPanel.Action.CLEAR_BG -> {
                BackgroundManager.setUri(this, null); BackgroundManager.setEnabled(this, false); smallReactor.setBackgroundBitmap(null); vibrate(15)
            }
            QuickPanel.Action.TOGGLE_LARGE -> { largeEnabled = !largeEnabled; updateLayerVisibility(force = true); vibrate(15) }
            QuickPanel.Action.TOGGLE_SMALL -> { smallEnabled = !smallEnabled; updateLayerVisibility(force = true); vibrate(15) }
            QuickPanel.Action.RESET -> { smallReactor.reset(); largeReactor.reset() }
            QuickPanel.Action.SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
            QuickPanel.Action.CLOSE -> stopSelf()
        }
        panel.hide()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { toast("系统版本过低"); return }
        upgradeForegroundType()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = mpm.getMediaProjection(resultCode, data) ?: run { toast("获取投影失败"); return }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    captureSource?.stop(); captureSource = null
                    projectionRef?.stop(); projectionRef = null
                    if (currentSource == SourceKind.CAPTURE) currentSource = SourceKind.SYNTH
                }
            }
        }, handler)
        val src = CaptureSource(this, projection, bandCount = 48)
        src.setAdaptiveGain(config.adaptiveGain)
        if (!src.start()) { projection.stop(); toast("机内音频启动失败，此应用可能禁止被录制"); return }
        captureSource?.stop(); captureSource = src; projectionRef = projection; mic.stop()
        currentSource = SourceKind.CAPTURE; vibrate(15)
    }

    private fun applyBackground() {
        if (!BackgroundManager.isEnabled(this)) { smallReactor.setBackgroundBitmap(null); return }
        val uri = BackgroundManager.getUri(this) ?: run { smallReactor.setBackgroundBitmap(null); return }
        val myToken = ++bgDecodeToken
        val tw = smallParams.width; val th = smallParams.height
        thread(name = "bg-decode") {
            val bmp = BackgroundManager.decode(this, uri, tw, th)
            handler.post { if (myToken == bgDecodeToken) smallReactor.setBackgroundBitmap(bmp) }
        }
    }

    private fun applyConfig() {
        config = ReactorConfig.load(this)
        smallReactor.applyConfig(config)
        largeReactor.applyConfig(config.copy(rodDensity = ReactorConfig.RodDensity.SPARSE))
        mic.setAdaptiveGain(config.adaptiveGain)
        captureSource?.setAdaptiveGain(config.adaptiveGain)
        restartFrameTick()
    }

    private fun restartFrameTick() {
        handler.removeCallbacks(frameTick)
        lastMs = 0L
        handler.postDelayed(frameTick, 1000L / config.fps)
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun vibrate(ms: Long) {
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reactor", NotificationManager.IMPORTANCE_LOW))
            noti = Notification.Builder(this, CHANNEL_ID).setContentTitle("控制棒悬浮窗").setContentText("正在运行").setSmallIcon(android.R.drawable.ic_menu_compass).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTI_ID, noti, type)
            } else startForeground(NOTI_ID, noti)
        }
    }

    private fun upgradeForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (hasMicPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try { startForeground(NOTI_ID, noti, type) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        mic.stop(); captureSource?.stop(); captureSource = null; projectionRef?.stop(); projectionRef = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        getSharedPreferences(ReactorConfig.PREF, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(configListener)
        if (::panel.isInitialized) panel.destroy(wm)
        if (::smallRoot.isInitialized) { try { wm.removeView(smallRoot) } catch (_: Exception) {} }
        if (::largeRoot.isInitialized) { try { wm.removeView(largeRoot) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
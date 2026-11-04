package com.example.reactor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.reactor.audio.AudioStatus
import com.example.reactor.ui.BackgroundManager
import com.example.reactor.ui.OverlayService
import com.example.reactor.ui.SettingsActivity
import com.example.reactor.util.AppLog
import com.example.reactor.util.UsageAccess

/**
 * 入口与权限中枢。
 *
 * 悬浮窗按钮、快捷面板都通过这里回到前台再申请敏感权限：
 * Android 12 起「后台启动前台服务 + 使用麦克风」会被系统拒绝，
 * 所以麦克风必须由可见的 Activity 发起。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var logText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var pendingStartLarge = true

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingStartLarge) {
            if (granted) {
                startServiceNow()
            } else {
                // 没给麦克风也允许启动：演示信号 + 机内音频都不需要它
                toast("未授予麦克风权限，将以演示信号启动")
                startServiceNow()
            }
        } else {
            if (granted) {
                startServiceAction(OverlayService.ACTION_TOGGLE_MIC)
                toast("正在启用麦克风")
            } else {
                toast("没有麦克风权限")
            }
            finishIfRequested()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 给不给都继续，前台服务本身不依赖通知权限
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CAPTURE_READY
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
            }
            startServiceCompat(intent)
        } else {
            toast("录屏授权被取消")
        }
        finishIfRequested()
    }

    private val pickBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            BackgroundManager.setUri(this, uri)
            BackgroundManager.setEnabled(this, true)
            notifyService(OverlayService.ACTION_REFRESH_BG)
            toast("背景已设置")
        }
        finishIfRequested()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 从快捷面板带过来的指令先处理，处理完直接退出，不闪界面
        if (handleCommand(intent)) return

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.text_status)
        detailText = findViewById(R.id.text_detail)
        logText = findViewById(R.id.text_log)
        logText.setOnClickListener { copyLog() }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startOverlayFlow()
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            toast("已停止")
            refreshStatus()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            openPermissionChecklist()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            refreshStatus()
            handler.post(statusTicker)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTicker)
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 700L)
        }
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val snapshot = AudioStatus.snapshot
        val running = OverlayService.isRunning
        statusText.text = when {
            !running -> "悬浮窗未运行"
            snapshot == null -> "悬浮窗运行中 · 等待音频数据"
            else -> snapshot.shortLine()
        }
        detailText.text = buildDetail(snapshot)

        val lines = AppLog.tail(18)
        logText.text = if (lines.isEmpty()) "（还没有日志）" else lines.joinToString("\n")
    }

    private fun copyLog() {
        try {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("reactor-log", AppLog.dump())
            )
            toast("日志已复制")
        } catch (e: Exception) {
            toast("复制失败：${e.javaClass.simpleName}")
        }
    }

    private fun buildDetail(snapshot: com.example.reactor.audio.AudioDiagnostics?): String {
        val builder = StringBuilder()
        builder.append("悬浮窗权限：").append(if (Settings.canDrawOverlays(this)) "已授权" else "未授权").append('\n')
        builder.append("使用情况访问：").append(if (hasUsagePermission()) "已授权" else "未授权（大窗不会显示）").append('\n')
        builder.append("麦克风权限：")
            .append(
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) "已授权" else "未授权"
            ).append('\n')
        if (snapshot != null) {
            builder.append("采样率：").append(snapshot.sampleRate).append(" Hz\n")
            builder.append("编码：").append(snapshot.encoding).append('\n')
            builder.append("AudioRecord：").append(snapshot.recordState).append('\n')
            builder.append("read 返回：").append(snapshot.lastReadResult).append('\n')
            builder.append("RMS：").append("%.4f".format(snapshot.rms))
                .append("  峰值：").append("%.4f".format(snapshot.peak)).append('\n')
            builder.append("FFT 最大幅度：").append("%.4f".format(snapshot.fftMax)).append('\n')
            snapshot.lastError?.let { builder.append("最近错误：").append(it).append('\n') }
        }
        return builder.toString().trimEnd()
    }

    // ==================== 启动流程 ====================

    private fun startOverlayFlow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先允许「显示在其他应用上层」")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        ensureNotificationPermission()
        ensureMicPermissionForStart()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 拒绝也不影响前台服务本身，只是看不到通知
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureMicPermissionForStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startServiceNow()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startServiceNow() {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_DISABLE_LARGE, !hasUsagePermission())
        }
        startServiceCompat(intent)
        toast("悬浮窗已启动")
        refreshStatus()
    }

    /**
     * 麦克风需要「可见的 Activity」才能合法启用：
     * Android 12 不允许后台把前台服务升级成 microphone 类型。
     */
    private fun enableMicFromForeground() {
        if (!OverlayService.isRunning) {
            startServiceNow()
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startServiceAction(OverlayService.ACTION_TOGGLE_MIC)
            toast("正在启用麦克风")
            finishIfRequested()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("系统版本低于 Android 10，不支持机内音频")
            finishIfRequested()
            return
        }
        if (!OverlayService.isRunning) startServiceNow()
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            toast("系统不支持录屏授权")
            finishIfRequested()
            return
        }
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openPermissionChecklist() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("权限检查")
            .setMessage(
                "1. 显示在其他应用上层（悬浮窗）\n" +
                    "2. 使用情况访问（判断是否在桌面，决定大窗显示）\n" +
                    "3. 麦克风（麦克风模式需要）\n\n" +
                    "点「去开启」会依次跳到对应系统页面。"
            )
            .setPositiveButton("去开启") { _, _ ->
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } else if (!hasUsagePermission()) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
    }

    // ==================== 指令分发 ====================

    private fun handleCommand(intent: Intent?): Boolean {
        val action = intent?.getStringExtra("action") ?: return false
        pendingStartLarge = false
        when (action) {
            "request_capture" -> requestCapture()
            "request_mic" -> enableMicFromForeground()
            "pick_background" -> pickBackgroundLauncher.launch(arrayOf("image/*"))
            "settings" -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
            else -> return false
        }
        return true
    }

    /** 由面板/通知进入时，处理完就退出，不要停在空界面上。 */
    private fun finishIfRequested() {
        if (!pendingStartLarge) finish()
    }

    private fun hasUsagePermission(): Boolean = UsageAccess.has(this)

    private fun startServiceAction(action: String) {
        startServiceCompat(Intent(this, OverlayService::class.java).setAction(action))
    }

    private fun notifyService(action: String) {
        startServiceAction(action)
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) {
            toast("启动失败：${e.javaClass.simpleName}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

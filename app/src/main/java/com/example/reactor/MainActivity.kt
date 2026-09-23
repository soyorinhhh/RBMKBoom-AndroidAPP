package com.example.reactor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.reactor.ui.BackgroundManager
import com.example.reactor.ui.OverlayService
import com.example.reactor.ui.SettingsActivity

class MainActivity : AppCompatActivity() {

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ensureUsageThenStart() }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CAPTURE_READY
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } else {
            Toast.makeText(this, "授权被取消", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private val pickBgLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            BackgroundManager.setUri(this, uri)
            BackgroundManager.setEnabled(this, true)
            notifyServiceRefreshBg()
            Toast.makeText(this, "背景已设置", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleIntentAction(intent)) return

        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)
            ) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                Toast.makeText(this, "允许后请返回再次点击", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ensureMicThenStart()
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: Intent?): Boolean {
        return when (intent?.getStringExtra("action")) {
            "request_capture" -> { handleCaptureRequest(); true }
            "pick_background" -> { pickBgLauncher.launch(arrayOf("image/*")); true }
            else -> false
        }
    }

    private fun handleCaptureRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "系统版本过低，不支持机内音频捕获", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun ensureMicThenStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) ensureUsageThenStart()
        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun ensureUsageThenStart() {
        if (hasUsagePermission()) {
            startOverlay()
        } else {
            AlertDialog.Builder(this)
                .setTitle("需要「使用情况访问」")
                .setMessage("用于判断是否在桌面，切换大/小悬浮窗。\n\n接下来打开的页面找到本应用并允许访问。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    Toast.makeText(this, "允许后请返回再次点击启动", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("仅用悬浮层") { _, _ ->
                    startOverlay(disableLarge = true)
                }
                .show()
        }
    }

    private fun hasUsagePermission(): Boolean {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 1000, end)
        return !stats.isNullOrEmpty()
    }

    private fun startOverlay(disableLarge: Boolean = false) {
        val i = Intent(this, OverlayService::class.java).apply {
            if (disableLarge) putExtra("disableLarge", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        finish()
    }

    private fun notifyServiceRefreshBg() {
        try {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_REFRESH_BG
            }
            startService(i)
        } catch (_: Exception) {}
    }
}
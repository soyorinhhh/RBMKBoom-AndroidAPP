package com.example.reactor.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 机内播放音频捕获（Android 10+）。
 *
 * 相对初版的修复点：
 * - MediaProjection 已失效时 `AudioPlaybackCaptureConfiguration.Builder` / `AudioRecord.Builder`
 *   会抛异常，这里逐个 try 住并把原因写进 lastError，不再静默返回 false；
 * - 注册 MediaProjection.Callback，用户点「停止共享」或投影被系统回收时立刻标记失效，
 *   避免后续一直读一个死掉的 AudioRecord；
 * - `stop()` 同样先 stop 再 release，保证阻塞读能立刻返回；
 * - 拒绝被捕获的应用（如部分播放器）会一直读 0，这里与「静音」区分开，交给引擎判断。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CaptureSource(
    private val projection: MediaProjection,
    private val preferredSampleRate: Int = 44100,
    private val targetSamples: Int = 1024,
    private val onFatalError: ((String) -> Unit)? = null
) : PcmSource {

    override val label: String = "CAPTURE"

    @Volatile override var sampleRate: Int = preferredSampleRate
        private set

    override val encodingName: String get() = "PCM 16bit mono (playback capture)"

    @Volatile override var lastError: String? = null
        private set

    @Volatile override var lastReadResult: Int = 0
        private set

    @Volatile private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var projectionStopped = false
    @Volatile private var callback: MediaProjection.Callback? = null

    /** 由 AudioEngine 的线程调用。 */
    override fun start(): Boolean {
        if (record != null) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastError = "系统版本低于 Android 10，不支持机内音频捕获"
            return false
        }
        lastError = null
        projectionStopped = false

        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                projectionStopped = true
                running = false
                onFatalError?.invoke("录屏授权已停止，机内音频中断")
            }
        }
        callback = cb
        try {
            projection.registerCallback(cb, null)
        } catch (e: Exception) {
            lastError = "注册 MediaProjection 回调失败：${e.javaClass.simpleName} ${e.message ?: ""}"
            // 回调注册失败不致命，继续尝试采集
        }

        val rates = intArrayOf(preferredSampleRate, 48000, 44100).distinct().filter { it > 0 }
        var lastFailure = "机内音频初始化失败"

        for (rate in rates) {
            val config = try {
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            } catch (e: Exception) {
                lastFailure = "播放捕获配置失败（投影可能已失效）：${e.javaClass.simpleName} ${e.message ?: ""}"
                break
            }

            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(if (minBuf > 0) minBuf else 0, targetSamples * 2 * 2)

            var created: AudioRecord? = null
            try {
                created = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } catch (e: Exception) {
                lastFailure = "AudioRecord 构造失败（$rate Hz）：${e.javaClass.simpleName} ${e.message ?: ""}"
                try { created?.release() } catch (_: Exception) {}
                continue
            }

            if (created.state != AudioRecord.STATE_INITIALIZED) {
                lastFailure = "$rate Hz 下 AudioRecord 未初始化"
                safeRelease(created)
                continue
            }

            try {
                created.startRecording()
            } catch (e: Exception) {
                lastFailure = "startRecording 失败：${e.javaClass.simpleName} ${e.message ?: ""}"
                safeRelease(created)
                continue
            }
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                lastFailure = "startRecording 后状态异常（recordingState=${created.recordingState}）"
                safeRelease(created)
                continue
            }

            sampleRate = rate
            record = created
            running = true
            lastError = null
            return true
        }

        lastError = lastFailure
        unregisterCallback()
        return false
    }

    override fun readPcm(dst: ShortArray): Int {
        if (projectionStopped) return AudioRecord.ERROR_DEAD_OBJECT
        val rec = record ?: return AudioRecord.ERROR_INVALID_OPERATION
        if (!running) return AudioRecord.ERROR_INVALID_OPERATION
        val result = try {
            rec.read(dst, 0, dst.size)
        } catch (e: Exception) {
            lastError = "read 抛异常：${e.javaClass.simpleName} ${e.message ?: ""}"
            AudioRecord.ERROR_INVALID_OPERATION
        }
        lastReadResult = result
        if (result < 0) {
            val reason = when (result) {
                AudioRecord.ERROR_DEAD_OBJECT -> "捕获对象失效（ERROR_DEAD_OBJECT）"
                AudioRecord.ERROR_INVALID_OPERATION -> "捕获操作非法（ERROR_INVALID_OPERATION）"
                AudioRecord.ERROR_BAD_VALUE -> "参数非法（ERROR_BAD_VALUE）"
                else -> "read 返回 $result"
            }
            lastError = reason
            running = false
            onFatalError?.invoke(reason)
        }
        return result
    }

    override fun stop() {
        val rec = record
        running = false
        try { rec?.stop() } catch (_: Exception) {}
        record = null
        if (rec != null) safeRelease(rec)
        unregisterCallback()
        lastReadResult = 0
    }

    private fun unregisterCallback() {
        val cb = callback ?: return
        callback = null
        try { projection.unregisterCallback(cb) } catch (_: Exception) {}
    }

    override fun recordStateText(): String {
        if (projectionStopped) return "投影已停止"
        val rec = record ?: return "未初始化"
        val state = if (rec.state == AudioRecord.STATE_INITIALIZED) "INITIALIZED" else "UNINITIALIZED"
        val recording = when (rec.recordingState) {
            AudioRecord.RECORDSTATE_RECORDING -> "RECORDING"
            AudioRecord.RECORDSTATE_STOPPED -> "STOPPED"
            else -> "recordingState=${rec.recordingState}"
        }
        return "$state / $recording"
    }

    @SuppressLint("MissingPermission")
    private fun safeRelease(rec: AudioRecord) {
        try { rec.release() } catch (_: Exception) {}
    }
}

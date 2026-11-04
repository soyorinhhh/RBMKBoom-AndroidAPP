package com.example.reactor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 麦克风采集。
 *
 * 相对初版的关键修复：
 * 1. 编码从 PCM_FLOAT 换成 **PCM 16bit**（兼容性最好，ColorOS 上更稳），读取与转换逻辑同步改写；
 * 2. `readPcm` 的负返回值（ERROR_DEAD_OBJECT / ERROR_INVALID_OPERATION / ERROR_BAD_VALUE）
 *    不再被当成「没声音」直接 continue —— 初版那样会在读挂掉之后变成 100% CPU 死循环；
 * 3. `stop()` 先 `AudioRecord.stop()` 解阻塞，再 join 线程，最后 release，
 *    初版是 join 在前、stop 在后，读线程卡在阻塞读里，资源释放会拖到超时甚至泄漏；
 * 4. 采样率优先 44100，失败回退 48000，缓冲区按 minBuf 与目标帧长取大；
 * 5. 全程记录诊断信息（状态、read 返回值、错误原因）。
 */
class MicSource(
    private val preferredSampleRate: Int = 44100,
    private val targetSamples: Int = 1024,
    private val onFatalError: ((String) -> Unit)? = null
) : PcmSource {

    override val label: String = "MIC"

    @Volatile override var sampleRate: Int = preferredSampleRate
        private set

    override val encodingName: String get() = "PCM 16bit mono"

    @Volatile override var lastError: String? = null
        private set

    @Volatile override var lastReadResult: Int = 0
        private set

    @Volatile private var record: AudioRecord? = null
    @Volatile private var running = false

    /** 由 AudioEngine 的线程调用。 */
    override fun start(): Boolean {
        if (record != null) return true
        lastError = null

        val rates = intArrayOf(preferredSampleRate, 48000, 44100)
            .distinct()
            .filter { it > 0 }

        var lastFailure = "AudioRecord 初始化失败"
        for (rate in rates) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                lastFailure = "设备不支持 $rate Hz / PCM16（getMinBufferSize=$minBuf）"
                continue
            }
            val bufSize = maxOf(minBuf, targetSamples * 2 * 2)
            val created = createRecord(rate, bufSize)
            if (created == null) {
                lastFailure = "$rate Hz 下无法初始化 AudioRecord"
                continue
            }
            sampleRate = rate
            record = created
            running = true
            try {
                created.startRecording()
            } catch (e: Exception) {
                lastFailure = "startRecording 失败：${e.javaClass.simpleName} ${e.message ?: ""}"
                releaseQuietly(created)
                record = null
                running = false
                continue
            }
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                lastFailure = "startRecording 后状态异常（state=${created.recordingState}）"
                releaseQuietly(created)
                record = null
                running = false
                continue
            }
            lastError = null
            return true
        }

        lastError = lastFailure
        return false
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(rate: Int, bufSize: Int): AudioRecord? {
        // MIC 最通用；UNPROCESSED 在部分机型会拒绝，放最后兜底
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED
        )
        for (source in sources) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate
                candidate.release()
            } catch (e: Exception) {
                lastError = "AudioRecord 构造失败（source=$source）：${e.javaClass.simpleName}"
                try { candidate?.release() } catch (_: Exception) {}
            }
        }
        return null
    }

    override fun readPcm(dst: ShortArray): Int {
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
            // 致命错误：通知引擎切回去，别在这里空转
            val reason = when (result) {
                AudioRecord.ERROR_DEAD_OBJECT -> "录音对象失效（ERROR_DEAD_OBJECT）"
                AudioRecord.ERROR_INVALID_OPERATION -> "录音操作非法（ERROR_INVALID_OPERATION）"
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
        // 先 stop 解开阻塞的 read()，否则读线程会一直挂在里面
        try { rec?.stop() } catch (_: Exception) {}
        record = null
        if (rec != null) releaseQuietly(rec)
        lastReadResult = 0
    }

    override fun recordStateText(): String {
        val rec = record ?: return "未初始化"
        val state = when (rec.state) {
            AudioRecord.STATE_INITIALIZED -> "INITIALIZED"
            AudioRecord.STATE_UNINITIALIZED -> "UNINITIALIZED"
            else -> "state=${rec.state}"
        }
        val recording = when (rec.recordingState) {
            AudioRecord.RECORDSTATE_RECORDING -> "RECORDING"
            AudioRecord.RECORDSTATE_STOPPED -> "STOPPED"
            else -> "recordingState=${rec.recordingState}"
        }
        return "$state / $recording"
    }

    private fun releaseQuietly(rec: AudioRecord) {
        try { rec.release() } catch (_: Exception) {}
    }
}

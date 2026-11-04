package com.example.reactor.audio

import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import kotlin.concurrent.thread

/** 三种音频输入模式。 */
enum class SourceKind(val cn: String) {
    SYNTH("演示信号"),
    MIC("麦克风"),
    CAPTURE("机内音频")
}

/** 一次诊断快照，界面直接展示。 */
data class AudioDiagnostics(
    val mode: SourceKind = SourceKind.SYNTH,
    val sourceLabel: String = "SYNTH",
    val running: Boolean = false,
    val sampleRate: Int = 0,
    val encoding: String = "-",
    val recordState: String = "-",
    val lastReadResult: Int = 0,
    val rms: Float = 0f,
    val peak: Float = 0f,
    val fftMax: Float = 0f,
    val validSamples: Int = 0,
    val lastError: String? = null,
    val updatedAtMs: Long = 0L
) {
    fun shortLine(): String {
        val head = "${mode.cn} · ${if (running) "运行中" else "未运行"}"
        if (!running && lastError == null) return head
        if (lastError != null) return "$head · 错误：$lastError"
        return "$head · ${sampleRate}Hz · RMS %.3f · 峰值 %.3f".format(rms, peak)
    }
}

/** 让 Activity 也能读到服务里引擎的状态（只读快照）。 */
object AudioStatus {
    @Volatile
    var snapshot: AudioDiagnostics? = null
}

/**
 * 音频引擎：所有采集与分析都在这一个线程里跑，渲染层只读最新快照。
 *
 * 这样做的原因：
 * - 避免三个源各自开线程 + 各自持有 FFT，切换时容易互相争抢录音资源；
 * - 分析线程与 UI 帧率解耦，15fps 渲染时不会因为分析阻塞主线程；
 * - 切换时统一走「先停后起」，不会出现两个 AudioRecord 同时活着。
 */
class AudioEngine(
    private val bandCount: Int = SpectrumAnalyzer.DEFAULT_BANDS,
    private val fftSize: Int = SpectrumAnalyzer.DEFAULT_FFT_SIZE
) {
    companion object {
        /** 演示信号的推送节奏（与渲染帧率无关）。 */
        private const val SYNTH_TICK_MS = 20L
    }

    private val analyzer = SpectrumAnalyzer(fftSize, bandCount)
    private val synth = SynthSource(bandCount, 8f)

    private val published = FloatArray(bandCount)
    private val publishLock = Any()

    private val workBands = FloatArray(bandCount)
    private val pcmBuffer = ShortArray(maxOf(fftSize * 2, 4096))

    @Volatile private var running = false
    @Volatile private var mode = SourceKind.SYNTH
    @Volatile private var source: PcmSource? = null
    @Volatile private var worker: Thread? = null

    @Volatile var adaptiveGain: Boolean = true
    @Volatile var sensitivity: Float = 1f

    /** 采集源致命错误时回调（服务据此切回演示模式）。 */
    @Volatile var onSourceDied: ((String) -> Unit)? = null

    private var gain = 1f
    private var silentFrames = 0

    fun currentMode(): SourceKind = mode

    fun currentDiagnostics(): AudioDiagnostics = diagnostics

    val isRunning: Boolean get() = running

    @Volatile
    private var diagnostics = AudioDiagnostics()

    /**
     * 停止当前源并启动新源。
     *
     * @param next 目标模式
     * @param projection CAPTURE 模式必需
     * @return 成功与否；失败时 diagnostics.lastError 有原因，引擎会退回到 SYNTH
     */
    @Synchronized
    fun switchTo(next: SourceKind, projection: MediaProjection? = null): Boolean {
        stopInternal()

        if (next == SourceKind.CAPTURE) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                fallbackToSynth("系统版本低于 Android 10，不支持机内音频捕获")
                return false
            }
            if (projection == null) {
                fallbackToSynth("没有有效的录屏授权")
                return false
            }
            val capture = CaptureSource(
                projection = projection,
                targetSamples = fftSize,
                onFatalError = { reason -> handleSourceDeath(reason) }
            )
            if (!capture.start()) {
                val reason = capture.lastError ?: "机内音频初始化失败"
                capture.stop()
                fallbackToSynth(reason)
                return false
            }
            source = capture
            mode = SourceKind.CAPTURE
            startWorker(capture)
            return true
        }

        if (next == SourceKind.MIC) {
            val mic = MicSource(
                targetSamples = fftSize,
                onFatalError = { reason -> handleSourceDeath(reason) }
            )
            if (!mic.start()) {
                val reason = mic.lastError ?: "麦克风初始化失败"
                mic.stop()
                fallbackToSynth(reason)
                return false
            }
            source = mic
            mode = SourceKind.MIC
            startWorker(mic)
            return true
        }

        source = null
        mode = SourceKind.SYNTH
        startSynthWorker()
        return true
    }

    /** 只停不换（服务销毁时用）。 */
    @Synchronized
    fun stop() {
        stopInternal()
        mode = SourceKind.SYNTH
        publishZero()
        publishDiagnostics(SourceKind.SYNTH, null)
    }

    /** 渲染层取最新频谱（线程安全，零分配）。 */
    fun snapshot(out: FloatArray) {
        synchronized(publishLock) {
            System.arraycopy(published, 0, out, 0, minOf(out.size, bandCount))
        }
    }

    /** 重置信道平滑状态（换源后避免残留上一路的形状）。 */
    fun resetAnalysis() {
        analyzer.reset()
        publishZero()
    }

    // ---------------- 内部实现 ----------------

    private fun fallbackToSynth(reason: String) {
        mode = SourceKind.SYNTH
        source = null
        diagnostics = AudioDiagnostics(
            mode = SourceKind.SYNTH,
            sourceLabel = "SYNTH",
            running = false,
            lastError = reason,
            updatedAtMs = SystemClock.elapsedRealtime()
        )
        AudioStatus.snapshot = diagnostics
        startSynthWorker()
    }

    private fun handleSourceDeath(reason: String) {
        if (mode == SourceKind.SYNTH) return
        // 采集线程自己调用过来的，不能在这里 join 自己
        thread(name = "audio-fallback", isDaemon = true) {
            synchronized(this) {
                if (mode == SourceKind.SYNTH) return@synchronized
                stopInternal()
                fallbackToSynth(reason)
            }
        }
    }

    private fun startSynthWorker() {
        analyzer.reset()
        running = true
        val startedAt = SystemClock.elapsedRealtime()
        worker = thread(name = "audio-synth", isDaemon = true) {
            while (running) {
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000f
                val t = elapsed % 8f
                val frame = synth.read(t)
                applyGainAndSensitivity(frame)
                synchronized(publishLock) {
                    System.arraycopy(frame, 0, published, 0, bandCount)
                }
                diagnostics = AudioDiagnostics(
                    mode = SourceKind.SYNTH,
                    sourceLabel = "SYNTH",
                    running = true,
                    sampleRate = 0,
                    encoding = "synth (严格 8s 循环)",
                    recordState = "生成中",
                    lastReadResult = 0,
                    rms = average(frame),
                    peak = maximum(frame),
                    fftMax = maximum(frame),
                    validSamples = fftSize,
                    lastError = null,
                    updatedAtMs = SystemClock.elapsedRealtime()
                )
                AudioStatus.snapshot = diagnostics
                try {
                    Thread.sleep(SYNTH_TICK_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun startWorker(src: PcmSource) {
        analyzer.reset()
        running = true
        silentFrames = 0
        worker = thread(name = "audio-worker-${src.label}", isDaemon = true) {
            while (running) {
                val read = src.readPcm(pcmBuffer)
                if (!running) break
                if (read < 0) {
                    // 采集源已经报错并回调过，这里退出即可
                    break
                }
                if (read == 0) {
                    // 没数据也不算错误，让出一下 CPU
                    try {
                        Thread.sleep(4)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                analyzer.analyze(pcmBuffer, read, workBands)
                applyGainAndSensitivity(workBands)
                synchronized(publishLock) {
                    System.arraycopy(workBands, 0, published, 0, bandCount)
                }
                publishDiagnostics(mode, src)
            }
        }
    }

    private fun publishDiagnostics(kind: SourceKind, src: PcmSource?, bands: FloatArray? = null) {
        val rms = analyzer.lastRms
        val peak = analyzer.lastPeak
        diagnostics = AudioDiagnostics(
            mode = kind,
            sourceLabel = src?.label ?: kind.name,
            running = running,
            sampleRate = src?.sampleRate ?: 0,
            encoding = src?.encodingName ?: "-",
            recordState = src?.recordStateText() ?: "-",
            lastReadResult = src?.lastReadResult ?: 0,
            rms = rms,
            peak = peak,
            fftMax = analyzer.lastMaxMagnitude,
            validSamples = analyzer.lastValidSamples,
            lastError = src?.lastError,
            updatedAtMs = SystemClock.elapsedRealtime()
        )
        AudioStatus.snapshot = diagnostics

        // 长时间完全没有电平时给上层一个提示（不是错误，只是环境安静或对方禁止捕获）
        if (kind == SourceKind.CAPTURE) {
            if (peak < 0.0015f) {
                silentFrames++
            } else {
                silentFrames = 0
            }
        }
    }

    private fun applyGainAndSensitivity(bands: FloatArray) {
        if (adaptiveGain) {
            var peak = 0f
            for (v in bands) if (v > peak) peak = v
            val target = when {
                peak > 0.05f -> 0.92f / peak      // 有声：把最高频段推到接近满幅
                peak > 0.005f -> 2.2f             // 很轻：适度放大
                else -> 3.0f                      // 几乎静音：尽量拉出细节
            }
            gain += (target - gain) * 0.06f
            gain = gain.coerceIn(0.6f, 4.2f)
        } else {
            gain += (1f - gain) * 0.2f
        }

        val sens = sensitivity.coerceIn(0.2f, 3f)
        for (i in bands.indices) {
            bands[i] = (bands[i] * gain * sens).coerceIn(0f, 1f)
        }
    }

    private fun stopInternal() {
        running = false
        val threadRef = worker
        val src = source
        source = null
        worker = null

        // 先停采集源解除阻塞读，再等线程退出
        try { src?.stop() } catch (_: Exception) {}
        if (threadRef != null && threadRef !== Thread.currentThread()) {
            try { threadRef.join(600) } catch (_: InterruptedException) {}
            if (threadRef.isAlive) threadRef.interrupt()
        }
    }

    private fun publishZero() {
        synchronized(publishLock) { published.fill(0f) }
    }

    private fun average(a: FloatArray): Float {
        var sum = 0f
        for (v in a) sum += v
        return if (a.isEmpty()) 0f else sum / a.size
    }

    private fun maximum(a: FloatArray): Float {
        var m = 0f
        for (v in a) if (v > m) m = v
        return m
    }
}

package com.example.reactor.audio

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.Q)
class CaptureSource(
    private val context: Context,
    private val projection: MediaProjection,
    private val bandCount: Int = 48
) {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FFT_SIZE = 1024
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
    }

    private val analyzer = SpectrumAnalyzer(FFT_SIZE, bandCount)
    private val latest = FloatArray(bandCount)
    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var adaptiveGainEnabled = true
    private var gain = 1f
    private var worker: Thread? = null
    private var record: AudioRecord? = null

    fun setAdaptiveGain(v: Boolean) { adaptiveGainEnabled = v }

    fun start(): Boolean {
        if (running) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, FFT_SIZE * 4 * 2)

        return try {
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); return false
            }

            record = rec
            running = true
            rec.startRecording()

            worker = thread(name = "capture-source", isDaemon = true) {
                val buf = FloatArray(FFT_SIZE)
                while (running) {
                    val n = rec.read(buf, 0, FFT_SIZE, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { Thread.sleep(8); continue }
                    if (n < FFT_SIZE) continue
                    val bands = analyzer.analyze(buf, 0)
                    if (adaptiveGainEnabled) applyGain(bands)
                    synchronized(lock) {
                        System.arraycopy(bands, 0, latest, 0, bandCount)
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    private fun applyGain(bands: FloatArray) {
        var peak = 0f
        for (v in bands) if (v > peak) peak = v
        val target = if (peak > 0.02f) 0.85f / peak else 1f
        gain += (target - gain) * 0.05f
        gain = gain.coerceIn(0.5f, 4f)
        for (i in bands.indices) bands[i] = (bands[i] * gain).coerceIn(0f, 1f)
    }

    fun read(out: FloatArray) {
        synchronized(lock) {
            System.arraycopy(latest, 0, out, 0, minOf(out.size, latest.size))
        }
    }

    fun stop() {
        running = false
        try { worker?.join(400) } catch (_: InterruptedException) {}
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        synchronized(lock) { latest.fill(0f) }
    }
}
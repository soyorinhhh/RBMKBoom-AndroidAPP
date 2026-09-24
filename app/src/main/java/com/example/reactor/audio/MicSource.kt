package com.example.reactor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

class MicSource(private val bandCount: Int = 48) {

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
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, FFT_SIZE * 4 * 2)

        val rec = createRecord(bufSize) ?: return false
        record = rec
        running = true
        rec.startRecording()

        worker = thread(name = "mic-source", isDaemon = true) {
            val buf = FloatArray(FFT_SIZE)
            while (running) {
                val n = rec.read(buf, 0, FFT_SIZE, AudioRecord.READ_BLOCKING)
                if (n < FFT_SIZE) continue
                val bands = analyzer.analyze(buf, 0)
                if (adaptiveGainEnabled) applyGain(bands)
                synchronized(lock) {
                    System.arraycopy(bands, 0, latest, 0, bandCount)
                }
            }
        }
        return true
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

    @SuppressLint("MissingPermission")
    private fun createRecord(bufSize: Int): AudioRecord? {
        val sources = intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        for (src in sources) {
            try {
                val rec = AudioRecord(src, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                rec.release()
            } catch (_: Exception) {}
        }
        return null
    }
}
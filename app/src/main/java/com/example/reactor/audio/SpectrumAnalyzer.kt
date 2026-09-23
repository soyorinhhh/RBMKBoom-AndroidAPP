package com.example.reactor.audio

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrumAnalyzer(
    private val fftSize: Int = 1024,
    private val bandCount: Int = 48
) {
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val window = FloatArray(fftSize) {
        (0.5 * (1.0 - cos(2.0 * Math.PI * it / (fftSize - 1)))).toFloat()
    }
    private val mags = FloatArray(fftSize / 2)
    private val bands = FloatArray(bandCount)
    private val bandStart = IntArray(bandCount)
    private val bandEnd = IntArray(bandCount)

    init {
        val minBin = 2
        val maxBin = fftSize / 2 - 1
        val logMin = ln(minBin.toDouble())
        val logMax = ln(maxBin.toDouble())
        for (i in 0 until bandCount) {
            val t0 = i.toDouble() / bandCount
            val t1 = (i + 1).toDouble() / bandCount
            bandStart[i] = Math.exp(logMin + (logMax - logMin) * t0)
                .toInt().coerceIn(minBin, maxBin)
            bandEnd[i] = Math.exp(logMin + (logMax - logMin) * t1)
                .toInt().coerceIn(bandStart[i] + 1, maxBin + 1)
        }
    }

    fun analyze(pcm: FloatArray, offset: Int = 0): FloatArray {
        for (i in 0 until fftSize) {
            re[i] = pcm[offset + i] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        for (i in 0 until fftSize / 2) {
            val r = re[i]; val m = im[i]
            mags[i] = sqrt(r * r + m * m)
        }
        val minDb = -85.0
        val maxDb = -15.0
        for (b in 0 until bandCount) {
            var sum = 0f; var cnt = 0
            for (i in bandStart[b] until bandEnd[b]) { sum += mags[i]; cnt++ }
            val avg = if (cnt > 0) sum / cnt else 0f
            val db = 20.0 * kotlin.math.log10((avg + 1e-9).toDouble())
            bands[b] = ((db - minDb) / (maxDb - minDb)).toFloat().coerceIn(0f, 1f)
        }
        return bands
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            val half = len shr 1
            while (i < n) {
                var curR = 1f; var curI = 0f
                for (k in 0 until half) {
                    val uR = re[i + k]; val uI = im[i + k]
                    val vR = re[i + k + half] * curR - im[i + k + half] * curI
                    val vI = re[i + k + half] * curI + im[i + k + half] * curR
                    re[i + k] = uR + vR; im[i + k] = uI + vI
                    re[i + k + half] = uR - vR; im[i + k + half] = uI - vI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
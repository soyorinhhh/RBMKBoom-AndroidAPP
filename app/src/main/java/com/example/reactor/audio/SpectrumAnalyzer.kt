package com.example.reactor.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1024 点 FFT + 48 频段分析。
 *
 * 相比初版做了这些修正：
 * - 输入带长度校验，绝不会因为 PCM 不足 fftSize 而 AIOOBE；
 * - 加了平滑（上升快、回落慢），避免逐帧闪烁；
 * - 每个频段取能量峰值而不是均值累加，低频不会被单根谱线漏掉；
 * - 归一化按「满幅正弦 ≈ 0 dBFS」标定，换设备不用重调；
 * - 每帧顺带产出 RMS / Peak / 最大幅度，供诊断使用；
 * - 所有中间数组复用，分析过程零分配。
 */
class SpectrumAnalyzer(
    private val fftSize: Int = DEFAULT_FFT_SIZE,
    private val bandCount: Int = DEFAULT_BANDS
) {
    companion object {
        const val DEFAULT_FFT_SIZE = 1024
        const val DEFAULT_BANDS = 48

        /** 低于这个 dBFS 视为静音。 */
        private const val FLOOR_DB = -82.0
        /** 满幅正弦经 Hann 窗后的单边幅度约 -12 dBFS。 */
        private const val CEIL_DB = -12.0
        private const val ATTACK = 0.62f
        private const val DECAY = 0.16f
    }

    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize 必须是 2 的幂" }
        require(bandCount > 0) { "bandCount 必须为正" }
    }

    private val n = fftSize
    private val half = n / 2

    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val hann = FloatArray(n) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat()
    }
    private val cosTable = FloatArray(half) { cos(2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(half) { sin(2.0 * PI * it / n).toFloat() }
    private val bitReverse = IntArray(n).also { table ->
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var v = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            table[i] = r
        }
    }

    private val mags = FloatArray(half)
    private val bandStart = IntArray(bandCount)
    private val bandEnd = IntArray(bandCount)
    private val smooth = FloatArray(bandCount)

    /** 归一化后的双边幅度，满幅正弦约 1.0。 */
    private val amplitudeScale = 2.0f / n.toFloat()

    // ---- 诊断量 ----
    var lastRms = 0f
        private set
    var lastPeak = 0f
        private set
    var lastMaxMagnitude = 0f
        private set
    var lastValidSamples = 0
        private set

    init {
        // 对数分布频段：低频窄、高频宽，和人耳感知更接近
        val minBin = 2
        val maxBin = half - 1
        val logMin = ln(minBin.toDouble())
        val logMax = ln(maxBin.toDouble())
        for (i in 0 until bandCount) {
            val t0 = i.toDouble() / bandCount
            val t1 = (i + 1).toDouble() / bandCount
            val start = Math.exp(logMin + (logMax - logMin) * t0).toInt().coerceIn(minBin, maxBin)
            val end = Math.exp(logMin + (logMax - logMin) * t1).toInt().coerceIn(start + 1, maxBin + 1)
            bandStart[i] = start
            bandEnd[i] = end
        }
    }

    /**
     * 分析一段 PCM16 单声道数据。
     *
     * 输入不足 fftSize 时用零补齐（而不是直接丢弃），这样麦克风启动初期也能出数据。
     *
     * @param pcm PCM16 采样
     * @param count 有效采样数，不得超过 pcm.size
     * @return 内部复用的 48 频段数组（0..1），调用方只读不要持有
     */
    fun analyze(pcm: ShortArray, count: Int, out: FloatArray): FloatArray {
        val usable = count.coerceIn(0, minOf(pcm.size, n))
        lastValidSamples = usable

        var rmsSum = 0.0
        var peak = 0f
        for (i in 0 until n) {
            val raw = if (i < usable) pcm[i].toFloat() / 32768f else 0f
            val abs = if (raw < 0f) -raw else raw
            if (abs > peak) peak = abs
            if (i < usable) rmsSum += (raw * raw).toDouble()
            re[i] = raw * hann[i]
            im[i] = 0f
        }
        lastPeak = peak
        lastRms = if (usable > 0) sqrt(rmsSum / usable).toFloat() else 0f

        fft()

        var maxMag = 0f
        for (i in 0 until half) {
            val value = sqrt(re[i] * re[i] + im[i] * im[i]) * amplitudeScale
            mags[i] = value
            if (value > maxMag) maxMag = value
        }
        lastMaxMagnitude = maxMag

        val range = CEIL_DB - FLOOR_DB
        for (b in 0 until bandCount) {
            var peakBin = 0f
            val from = bandStart[b]
            val to = bandEnd[b]
            for (i in from until to) {
                if (mags[i] > peakBin) peakBin = mags[i]
            }
            val db = 20.0 * kotlin.math.log10((peakBin + 1e-9).toDouble())
            val target = ((db - FLOOR_DB) / range).toFloat().coerceIn(0f, 1f)

            val current = smooth[b]
            val next = if (target > current) {
                current + (target - current) * ATTACK
            } else {
                current + (target - current) * DECAY
            }
            smooth[b] = next.coerceIn(0f, 1f)
        }

        System.arraycopy(smooth, 0, out, 0, minOf(out.size, bandCount))
        return out
    }

    /** 数据断流时把频谱缓慢拉回 0，避免停在最后一帧。 */
    fun decay(out: FloatArray, factor: Float = 0.88f) {
        for (i in smooth.indices) {
            smooth[i] = (smooth[i] * factor).coerceAtMost(smooth[i])
            if (smooth[i] < 0.001f) smooth[i] = 0f
        }
        System.arraycopy(smooth, 0, out, 0, minOf(out.size, bandCount))
    }

    fun reset() {
        smooth.fill(0f)
        mags.fill(0f)
        lastRms = 0f
        lastPeak = 0f
        lastMaxMagnitude = 0f
        lastValidSamples = 0
    }

    /** 原地基 2 迭代 FFT（输入已按位反转索引重排）。 */
    private fun fft() {
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val step = n / len
            val halfLen = len shr 1
            var start = 0
            while (start < n) {
                var k = 0
                var j = 0
                while (j < halfLen) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val i0 = start + j
                    val i1 = i0 + halfLen
                    val vr = re[i1] * wr - im[i1] * wi
                    val vi = re[i1] * wi + im[i1] * wr
                    re[i1] = re[i0] - vr
                    im[i1] = im[i0] - vi
                    re[i0] += vr
                    im[i0] += vi
                    k += step
                    j++
                }
                start += len
            }
            len = len shl 1
        }
    }
}

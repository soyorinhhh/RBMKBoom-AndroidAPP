package com.example.reactor.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 演示信号：无需任何权限，用于测试渲染 / 动画 / 悬浮窗。
 *
 * 周期严格 8 秒：所有分量都取 1/8 Hz 的整数倍，且拍点按 1 秒对齐，
 * 因此 t 与 t+8 时输出逐位相同，循环边界天然平滑，不会跳变。
 */
class SynthSource(
    private val bandCount: Int = 48,
    private val loopSeconds: Float = 8f
) {
    private val w = (2.0 * PI / loopSeconds).toFloat()
    private val out = FloatArray(bandCount)

    // 预计算每个频段的两路频率倍数与相位，避免每帧重复计算
    private val k1 = IntArray(bandCount)
    private val k2 = IntArray(bandCount)
    private val phase1 = FloatArray(bandCount)
    private val phase2 = FloatArray(bandCount)
    private val radialGain = FloatArray(bandCount)
    private val pulseGain = FloatArray(bandCount)

    init {
        for (i in 0 until bandCount) {
            val u = i.toFloat() / (bandCount - 1).coerceAtLeast(1)
            k1[i] = 1 + Math.round(u * 5)          // 1..6 个整拍
            k2[i] = 3 + Math.round(u * 9)          // 3..12 个整拍
            phase1[i] = u * 6f
            phase2[i] = u * 11f
            radialGain[i] = 0.55f + 0.45f * cos(u * PI.toFloat() * 0.5f)
            pulseGain[i] = 0.45f * (1f - u * 0.55f)
        }
    }

    /**
     * @param t 秒，调用方负责取模；t 与 t+8 的输出完全一致
     * @return 内部复用的数组，调用方只读
     */
    fun read(t: Float): FloatArray {
        val beat = sin(w * 8f * t)                      // 周期 1 秒
        val pulse = if (beat > 0f) {
            var p = beat
            p *= p; p *= p; p *= p                      // ^8，得到窄脉冲
            p
        } else 0f

        for (i in 0 until bandCount) {
            var v = 0.40f +
                0.22f * sin(w * k1[i] * t + phase1[i]) +
                0.13f * sin(w * k2[i] * t + phase2[i])
            v += pulse * pulseGain[i]
            v *= radialGain[i]
            out[i] = v.coerceIn(0f, 1f)
        }
        return out
    }
}

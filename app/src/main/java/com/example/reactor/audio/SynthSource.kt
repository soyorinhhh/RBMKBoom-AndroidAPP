package com.example.reactor.audio

import kotlin.math.cos
import kotlin.math.sin

class SynthSource(
    private val bandCount: Int = 48,
    private val loopSeconds: Float = 8f
) {
    private val w = (2.0 * Math.PI / loopSeconds).toFloat()
    private val out = FloatArray(bandCount)

    fun read(t: Float): FloatArray {
        for (i in 0 until bandCount) {
            val u = i.toFloat() / (bandCount - 1)
            val k1 = 1 + Math.round(u * 5)
            val k2 = 3 + Math.round(u * 9)
            var v = 0.40f
                + 0.22f * sin(w * k1 * t + u * 6f)
                + 0.13f * sin(w * k2 * t + u * 11f)
            val beat = sin(w * 8 * t)
            v += Math.pow(beat.coerceAtLeast(0f).toDouble(), 8.0).toFloat() * 0.45f * (1f - u * 0.55f)
            v *= 0.55f + 0.45f * cos(u * Math.PI.toFloat() * 0.5f)
            out[i] = v.coerceIn(0f, 1f)
        }
        return out
    }
}
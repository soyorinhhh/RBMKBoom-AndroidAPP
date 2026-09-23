package com.example.reactor.config

import android.content.Context

data class ReactorConfig(
    val fps: Int = 15,
    val sensitivity: Float = 1.0f,
    val adaptiveGain: Boolean = true,
    val rodDensity: RodDensity = RodDensity.NORMAL,
    val feel: Feel = Feel.NORMAL,
    val theme: Theme = Theme.GREEN
) {
    enum class RodDensity(val spacingDp: Float) { SPARSE(30f), NORMAL(21f), DENSE(14f) }
    enum class Feel(val spring: Float, val damping: Float) {
        SOFT(0.22f, 0.75f), NORMAL(0.34f, 0.68f), HARD(0.48f, 0.58f), CHAOS(0.55f, 0.50f)
    }
    enum class Theme(val low: Int, val mid: Int, val high: Int) {
        GREEN(0xFF4DFFA6.toInt(), 0xFFFFB454.toInt(), 0xFFFF3B30.toInt()),
        COBALT(0xFF5DB4FF.toInt(), 0xFFB366FF.toInt(), 0xFFFF4D6E.toInt()),
        AMBER(0xFFFFD24D.toInt(), 0xFFFF944D.toInt(), 0xFFFF3B30.toInt()),
        CRT(0xFF7FFFAA.toInt(), 0xFFD4FF7F.toInt(), 0xFFFF7F7F.toInt()),
        MONO(0xFFCCCCCC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF5555.toInt())
    }

    companion object {
        const val PREF = "reactor_config"
        fun load(ctx: Context): ReactorConfig {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return ReactorConfig(
                fps = p.getString("fps", "15")?.toIntOrNull() ?: 15,
                sensitivity = p.getInt("sensitivity", 10) / 10f,
                adaptiveGain = p.getBoolean("adaptiveGain", true),
                rodDensity = enumOr(p.getString("rodDensity", null), RodDensity.NORMAL),
                feel = enumOr(p.getString("feel", null), Feel.NORMAL),
                theme = enumOr(p.getString("theme", null), Theme.GREEN)
            )
        }
        private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def
    }
}
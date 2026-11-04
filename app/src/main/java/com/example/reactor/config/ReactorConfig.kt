package com.example.reactor.config

import android.content.Context

/**
 * 所有可视化/性能参数。
 *
 * 读取时对非法值一律回落到默认值：SharedPreferences 里的字符串可能来自旧版本或手改，
 * 不能让一次脏数据把渲染搞崩。
 */
data class ReactorConfig(
    val fps: Int = 15,
    val sensitivity: Float = 1.0f,
    val adaptiveGain: Boolean = true,
    val rodDensity: RodDensity = RodDensity.NORMAL,
    val feel: Feel = Feel.NORMAL,
    val theme: Theme = Theme.GREEN,
    val bandCount: Int = 48
) {
    /** 控制棒密度：决定环数与每环扇区数。 */
    enum class RodDensity(val rings: Int, val sectors: Int, val label: String) {
        SPARSE(9, 32, "稀疏"),
        NORMAL(14, 52, "标准"),
        DENSE(18, 64, "密集")
    }

    /** 物理手感：弹簧刚度与阻尼。 */
    enum class Feel(val spring: Float, val damping: Float, val label: String) {
        SOFT(0.22f, 0.75f, "软"),
        NORMAL(0.34f, 0.68f, "标准"),
        HARD(0.48f, 0.58f, "硬"),
        CHAOS(0.58f, 0.52f, "混沌")
    }

    enum class Theme(val label: String) {
        GREEN("经典绿"),
        COBALT("钴蓝"),
        AMBER("琥珀"),
        CRT("CRT 荧光"),
        MONO("单色")
    }

    companion object {
        const val PREF = "reactor_config"

        const val KEY_FPS = "fps"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_ADAPTIVE_GAIN = "adaptiveGain"
        const val KEY_ROD_DENSITY = "rodDensity"
        const val KEY_FEEL = "feel"
        const val KEY_THEME = "theme"

        /** 帧率只允许 15 / 20 / 30，其它值一律回落到 15。 */
        private val ALLOWED_FPS = setOf(15, 20, 30)

        fun load(ctx: Context): ReactorConfig {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val fps = p.getString(KEY_FPS, null)?.toIntOrNull()?.takeIf { it in ALLOWED_FPS } ?: 15
            val sensitivityRaw = p.getInt(KEY_SENSITIVITY, 10).coerceIn(0, 100)
            return ReactorConfig(
                fps = fps,
                // 0..100 → 0.2..3.0，默认 10 → 1.0
                sensitivity = (sensitivityRaw / 10f).coerceIn(0.2f, 3.0f),
                adaptiveGain = p.getBoolean(KEY_ADAPTIVE_GAIN, true),
                rodDensity = enumOr(p.getString(KEY_ROD_DENSITY, null), RodDensity.NORMAL),
                feel = enumOr(p.getString(KEY_FEEL, null), Feel.NORMAL),
                theme = enumOr(p.getString(KEY_THEME, null), Theme.GREEN)
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            if (name == null) fallback
            else enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
    }
}

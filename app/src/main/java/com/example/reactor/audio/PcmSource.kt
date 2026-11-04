package com.example.reactor.audio

/**
 * 统一的 PCM 采集接口。
 *
 * 渲染层不直接依赖 MicSource / CaptureSource，只认这个接口 + AudioEngine 产出的频谱。
 */
interface PcmSource {
    /** 日志/诊断用的名字。 */
    val label: String

    val sampleRate: Int

    /** 例如 "PCM 16bit mono"。 */
    val encodingName: String

    /** 初始化并开始录音。返回 false 时 [lastError] 里有原因。 */
    fun start(): Boolean

    /**
     * 阻塞读取一段 PCM16。
     * @return 读到的采样数；负数表示出错（见 AudioRecord.ERROR_*）
     */
    fun readPcm(dst: ShortArray): Int

    /** 可重复调用、可并发调用的安全停止。 */
    fun stop()

    /** 最近一次错误描述；没有错误为 null。 */
    val lastError: String?

    /** AudioRecord 的当前状态字符串，用于诊断。 */
    fun recordStateText(): String

    /** 最近一次 readPcm 的返回值。 */
    val lastReadResult: Int
}

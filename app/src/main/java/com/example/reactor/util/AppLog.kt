package com.example.reactor.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量运行日志环。
 *
 * 这个项目没法用 ADB 抓 logcat，所以把关键事件留在进程内的环形缓冲里，
 * 主界面直接把最近几十行显示出来，长按可复制 —— 这样即使服务崩了、窗口没了，
 * 也还能看到最后一次发生了什么。
 */
object AppLog {
    private const val MAX_LINES = 240
    private const val TAG_PREFIX = "Reactor"

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()

    fun add(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        Log.i("$TAG_PREFIX/$tag", message)
    }

    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }

    /** 最近 [count] 行，给界面用。 */
    fun tail(count: Int = 14): List<String> = synchronized(buffer) {
        val total = buffer.size
        buffer.toList().subList(maxOf(0, total - count), total)
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }
}

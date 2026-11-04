package com.example.reactor.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 「使用情况访问」权限判断。
 *
 * 首版用 `queryUsageStats(...).isNullOrEmpty()` 判断，在 ColorOS 上不可靠：
 * 有权限也可能返回空列表，于是应用反复索要权限 —— 用户授了权，界面还说没授。
 *
 * 正确做法是查 AppOps 的 GET_USAGE_STATS 操作是否 ALLOWED。
 */
object UsageAccess {

    fun has(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                // MODE_DEFAULT 时说明没有显式设置，交给二次判断
                AppOpsManager.MODE_DEFAULT -> fallbackQuery(context)
                else -> false
            }
        } catch (e: Exception) {
            AppLog.add("权限", "读取使用情况权限失败：${e.javaClass.simpleName}")
            false
        }
    }

    /** 兜底：真的查一次最近的使用记录，非空视为已授权。 */
    private fun fallbackQuery(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
                ?: return false
            val end = System.currentTimeMillis()
            !manager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                end - 60_000,
                end
            ).isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}

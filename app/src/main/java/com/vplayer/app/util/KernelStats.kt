package com.vplayer.app.util

import android.content.Context
import com.vplayer.app.player.engine.Kernel

/**
 * 各内核的起播耗时（点播放 → 首帧），存在本机，设置页显示平均值，方便对比哪个内核在自己的网络 / 源上更快。
 * 每条也会写进播放日志（「我的 → 导出日志」）。
 */
class KernelStats(context: Context) {
    data class Entry(val kernel: Kernel, val count: Int, val avgMs: Long, val lastMs: Long, val bestMs: Long)

    private val sp = context.getSharedPreferences("kernel_stats", Context.MODE_PRIVATE)

    fun record(kernel: Kernel, ms: Long) {
        if (ms <= 0 || ms > 120_000) return
        val k = kernel.id
        val n = sp.getInt("${k}_n", 0)
        val total = sp.getLong("${k}_t", 0)
        val best = sp.getLong("${k}_b", Long.MAX_VALUE)
        // 只保留最近约 50 次的平均：超过后按比例缩小累计值
        val (n2, t2) = if (n >= 50) 49 to total * 49 / n else n to total
        sp.edit().putInt("${k}_n", n2 + 1).putLong("${k}_t", t2 + ms).putLong("${k}_l", ms).putLong("${k}_b", minOf(best, ms)).apply()
    }

    fun summary(): List<Entry> = listOf(Kernel.EXO, Kernel.IJK, Kernel.SYSTEM).mapNotNull { kn ->
        val n = sp.getInt("${kn.id}_n", 0)
        if (n == 0) null else Entry(kn, n, sp.getLong("${kn.id}_t", 0) / n, sp.getLong("${kn.id}_l", 0), sp.getLong("${kn.id}_b", 0))
    }

    fun reset() = sp.edit().clear().apply()
}

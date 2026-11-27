package com.vplayer.app.player

import com.vplayer.app.data.model.PlayLine

/** 双击区域：左 35% 后退、右 35% 前进、中间暂停 / 播放 */
enum class TapZone {
    LEFT, CENTER, RIGHT;

    companion object {
        fun of(x: Float, width: Float): TapZone = when {
            width <= 0f -> CENTER
            x < width * 0.35f -> LEFT
            x > width * 0.65f -> RIGHT
            else -> CENTER
        }
    }
}

/**
 * 长按：右半屏 = 按设置倍速快进；左半屏 = 连续快退。
 * ExoPlayer 不能倒放，所以快退是每 [TICK_MS] 毫秒往回 seek 一次，步长随倍速缩放（3x ≈ 1.5 秒）。
 */
object HoldSeek {
    const val TICK_MS = 200L
    const val STALL_FAILOVER_MS = 8_000L

    fun isRewind(x: Float, width: Float) = width > 0f && x < width / 2f

    fun rewindStepMs(speed: Float): Long = (1500f * speed / 3f).toLong().coerceIn(400L, 5000L)

    fun nextRewindPos(pos: Long, speed: Float): Long = (pos - rewindStepMs(speed)).coerceAtLeast(0L)

    fun speedLabel(speed: Float): String =
        if (speed % 1f == 0f) "${speed.toInt()}x" else "${String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')}x"

    fun label(rewind: Boolean, speed: Float): String =
        if (rewind) "◀◀ 快退 ${speedLabel(speed)}" else "快进 ${speedLabel(speed)} ▶▶"
}

/** 自动换线：从当前线路往后找下一条没试过、且有内容的线路；集数按同名优先，否则同序号 */
object Failover {
    fun nextLine(lines: List<PlayLine>, current: Int, ep: Int, tried: Set<Int>): Pair<Int, Int>? {
        if (lines.size < 2) return null
        val epName = lines.getOrNull(current)?.episodes?.getOrNull(ep)?.name
        for (k in 1 until lines.size) {
            val i = (current + k) % lines.size
            if (i in tried) continue
            val eps = lines[i].episodes
            if (eps.isEmpty()) continue
            val byName = epName?.let { n -> eps.indexOfFirst { it.name == n } }?.takeIf { it >= 0 }
            val target = byName ?: ep.takeIf { it in eps.indices } ?: continue
            return i to target
        }
        return null
    }
}

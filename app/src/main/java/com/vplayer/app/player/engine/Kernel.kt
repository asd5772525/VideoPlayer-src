package com.vplayer.app.player.engine

import com.vplayer.app.data.prefs.AppSettings

/** 播放内核 */
enum class Kernel(val id: String, val label: String) {
    AUTO("auto", "自动"),
    EXO("exo", "ExoPlayer"),
    IJK("ijk", "IJK"),
    SYSTEM("system", "系统播放器");

    companion object {
        fun of(id: String?): Kernel = entries.firstOrNull { it.id.equals(id?.trim(), true) } ?: AUTO
    }
}

/**
 * 选内核的规则（纯函数，便于单元测试）：
 * - App 里手动选了 ExoPlayer / IJK / 系统 → 一律用它
 * - 「自动」→ 播放源配置了首选内核就用它，否则 ExoPlayer
 * - IJK 的 native 库加载失败（例如 32 位 x86 设备装了通用包）→ 退回系统播放器
 * - 只有「自动」模式下，遇到解码 / 格式类错误才换下一个内核（Exo → IJK → 系统），网络类错误照常换解析 / 换线路
 */
object KernelPolicy {
    private val CHAIN = listOf(Kernel.EXO, Kernel.IJK, Kernel.SYSTEM)

    fun available(k: Kernel, ijkOk: Boolean): Kernel = if (k == Kernel.IJK && !ijkOk) Kernel.SYSTEM else k

    fun initial(user: Kernel, sourcePref: String?, ijkOk: Boolean): Kernel {
        val k = if (user != Kernel.AUTO) user else Kernel.of(sourcePref).takeIf { it != Kernel.AUTO } ?: Kernel.EXO
        return available(k, ijkOk)
    }

    /** 解码 / 容器格式类错误（PlaybackException 错误码，常量值写死以便 JVM 测试） */
    fun isFormatError(code: Int): Boolean =
        code in 3001..3004 ||   // PARSING_CONTAINER_MALFORMED / MANIFEST_MALFORMED / CONTAINER_UNSUPPORTED / MANIFEST_UNSUPPORTED
            code in 4001..4005 || // DECODER_INIT_FAILED … DECODING_FORMAT_UNSUPPORTED
            code == 5001          // AUDIO_TRACK_INIT_FAILED（部分音频编码 Exo 不支持）

    /** @return 换到哪个内核；null = 不换内核 */
    fun fallback(user: Kernel, current: Kernel, errorCode: Int, tried: Set<Kernel>, ijkOk: Boolean): Kernel? {
        if (user != Kernel.AUTO || !isFormatError(errorCode)) return null
        return CHAIN.map { available(it, ijkOk) }.distinct().firstOrNull { it != current && it !in tried }
    }

    /** IJK / 系统 MediaPlayer 的 onError(what, extra) → Media3 PlaybackException 错误码 */
    fun nativeErrorCode(what: Int, extra: Int): Int = when {
        what == -1010 || extra == -1010 -> 4005 // MEDIA_ERROR_UNSUPPORTED → DECODING_FORMAT_UNSUPPORTED
        what == -1007 || extra == -1007 -> 3001 // MEDIA_ERROR_MALFORMED → PARSING_CONTAINER_MALFORMED
        what == -110 || extra == -110 -> 2002   // MEDIA_ERROR_TIMED_OUT → IO_NETWORK_CONNECTION_TIMEOUT
        what == 200 -> 4005                     // NOT_VALID_FOR_PROGRESSIVE_PLAYBACK
        else -> 2000                            // 其余（含 IJK 的 -10000 打开失败）按网络错误处理 → 换解析 / 线路
    }
}

/** 一套加载参数（预设或自定义） */
data class PlaybackProfile(
    val id: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startMs: Int,        // 起播前至少缓冲多少
    val rebufferMs: Int,     // 卡顿后至少缓冲多少才继续
    val cacheMb: Int,
    val preloadNext: Boolean,
    val connectTimeoutSec: Int,
    val readTimeoutSec: Int,
    val dnsPrefetch: Boolean,
) {
    /** ExoPlayer 的约束：start / rebuffer ≤ min ≤ max */
    fun sane(): PlaybackProfile {
        val max = maxBufferMs.coerceIn(5_000, 600_000)
        val min = minBufferMs.coerceIn(1_000, max)
        return copy(
            maxBufferMs = max, minBufferMs = min,
            startMs = startMs.coerceIn(100, min), rebufferMs = rebufferMs.coerceIn(100, min),
            cacheMb = cacheMb.coerceIn(64, 4096),
            connectTimeoutSec = connectTimeoutSec.coerceIn(2, 60), readTimeoutSec = readTimeoutSec.coerceIn(3, 120),
        )
    }

    fun summary(): String = "起播 ${fmt(startMs)} · 缓冲 ${minBufferMs / 1000}~${maxBufferMs / 1000} 秒 · 卡顿恢复 ${fmt(rebufferMs)} · " +
        "缓存 ${cacheMb}MB · 超时 ${connectTimeoutSec}/${readTimeoutSec} 秒" + (if (preloadNext) " · 预加载下一集" else "") + (if (dnsPrefetch) " · DNS 预解析" else "")

    private fun fmt(ms: Int) = if (ms % 1000 == 0) "${ms / 1000} 秒" else "%.1f 秒".format(java.util.Locale.US, ms / 1000f)
}

object PlaybackProfiles {
    data class Preset(val id: String, val label: String, val hint: String)

    val PRESETS = listOf(
        Preset("fast", "极速起播", "缓冲半秒就开播，适合网速好的时候"),
        Preset("balanced", "均衡", "默认，起播快也不容易卡（与 v1.1 相同）"),
        Preset("smooth", "流畅优先（大缓冲）", "多缓冲一会儿再播，网络不稳时更少卡顿，更费流量"),
        Preset("custom", "自定义", "自己设置每一项"),
    )

    val FAST = PlaybackProfile("fast", 4_000, 30_000, 500, 1_500, 256, true, 5, 10, true)
    val BALANCED = PlaybackProfile("balanced", 15_000, 30_000, 1_000, 2_000, 512, true, 8, 15, true)
    val SMOOTH = PlaybackProfile("smooth", 30_000, 120_000, 2_500, 5_000, 1024, true, 15, 30, true)

    fun of(s: AppSettings): PlaybackProfile = when (s.preset) {
        "fast" -> FAST
        "smooth" -> SMOOTH
        "custom" -> PlaybackProfile(
            "custom", s.minBufferSeconds * 1000, s.bufferSeconds * 1000, s.startMs, s.rebufferMs, s.cacheSizeMb,
            s.preloadNext, s.connectTimeoutSec, s.readTimeoutSec, s.dnsPrefetch,
        )
        else -> BALANCED
    }.sane()

    fun label(id: String) = PRESETS.firstOrNull { it.id == id }?.label ?: "均衡"
}

package com.vplayer.app.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.vplayer.app.player.PlayerManager
import com.vplayer.app.player.ad.AdRule

/** 一次播放请求：地址 + 媒体 id（vodKey|line|ep）+ 请求头 + 去广告规则 */
data class MediaRequest(
    val url: String,
    val mediaId: String,
    val headers: Map<String, String> = emptyMap(),
    val adRule: AdRule? = null,
    val forceHls: Boolean = false,
)

/**
 * 播放内核抽象。界面和播放逻辑只通过 Media3 的 [Player] 接口操作播放器（播放 / 暂停 / 进度 / 倍速 / 画面），
 * 所以手势、进度记忆、倍速、跳片头片尾、自动下一集、自动换线对所有内核都一样；
 * 只有「怎么加载一个地址」和少数 ExoPlayer 独有的能力（播放列表预缓冲、关键帧快速 seek）放在这里。
 */
interface PlayerEngine {
    val kind: Kernel
    val player: Player

    /** 加载并 prepare（不负责开始播放，调用方设置 playWhenReady） */
    suspend fun load(req: MediaRequest, startMs: Long)

    /** 把下一集加入播放列表提前缓冲；不支持的内核返回 false（下一集地址已提前解析好，切集同样很快） */
    fun appendNext(req: MediaRequest): Boolean = false

    /** 自动切到下一集后，移除播放列表里已经播完的条目 */
    fun dropPlayed() {}

    fun setPauseAtEnd(pause: Boolean) {}

    /** 长按快退时用关键帧 seek（更快）；只有 ExoPlayer 支持 */
    fun setFastSeek(fast: Boolean) {}

    /** 当前下载速度（字节 / 秒），拿不到返回 -1 */
    fun downloadSpeedBps(): Long = -1

    /** 是否支持 M3U8 去插播广告 */
    val adFilterSupported: Boolean

    fun release()
}

/** ExoPlayer（Media3）：默认内核，复用 [PlayerManager] 里全局唯一的实例（详情页预热也用它） */
@OptIn(UnstableApi::class)
class ExoEngine(private val pm: PlayerManager) : PlayerEngine {
    override val kind = Kernel.EXO
    private val exo: ExoPlayer = pm.obtain()
    override val player: Player get() = exo
    override val adFilterSupported = true

    override suspend fun load(req: MediaRequest, startMs: Long) {
        exo.setMediaSource(
            pm.buildMediaSource(req.url, req.mediaId, forceHls = req.forceHls, headers = req.headers, adRule = req.adRule),
            startMs.coerceAtLeast(0),
        )
        exo.prepare()
        pm.markPrepared(req.mediaId, req.url)
    }

    override fun appendNext(req: MediaRequest): Boolean {
        if (exo.mediaItemCount > exo.currentMediaItemIndex + 1) exo.removeMediaItems(exo.currentMediaItemIndex + 1, exo.mediaItemCount)
        exo.addMediaSource(pm.buildMediaSource(req.url, req.mediaId, headers = req.headers, adRule = req.adRule))
        return true
    }

    override fun dropPlayed() {
        val idx = exo.currentMediaItemIndex
        if (idx > 0) exo.removeMediaItems(0, idx)
    }

    override fun setPauseAtEnd(pause: Boolean) { exo.pauseAtEndOfMediaItems = pause }

    override fun setFastSeek(fast: Boolean) { exo.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT) }

    override fun downloadSpeedBps(): Long = pm.bandwidthMeter.bitrateEstimate / 8

    /** 全局共享，不在这里释放（播放页退出时 PlayerManager.stop()） */
    override fun release() {}
}

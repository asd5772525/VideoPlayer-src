package com.vplayer.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.vplayer.app.data.net.DEFAULT_UA
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.prefs.SettingsRepository
import java.io.File

/**
 * 全局唯一的 ExoPlayer 持有者。
 * - 详情页打开即对续播集 [prewarm]（setMediaSource + prepare，不播放），进入播放页直接起播
 * - 低起播缓冲（bufferForPlaybackMs = 1000ms），可配置最大缓冲
 * - OkHttp 数据源（共享连接池 / DNS 缓存）+ SimpleCache LRU 边播边缓存
 * - 硬解开关：关闭时优先选择软件解码器
 */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context, private val settings: SettingsRepository) {

    private var player: ExoPlayer? = null
    private var configKey: String? = null

    /** 当前已 prepare 的媒体 key（vodKey|line|ep），用于播放页复用预热结果 */
    var preparedKey: String? = null
        private set
    var preparedUrl: String? = null
        private set

    /** 播放页正在使用 */
    var inUse: Boolean = false

    val bandwidthMeter: DefaultBandwidthMeter by lazy { DefaultBandwidthMeter.getSingletonInstance(context) }

    companion object {
        @Volatile
        private var simpleCache: SimpleCache? = null

        fun cache(context: Context, sizeMb: Int): SimpleCache = simpleCache ?: synchronized(this) {
            simpleCache ?: SimpleCache(
                File(context.cacheDir, "media"),
                LeastRecentlyUsedCacheEvictor(sizeMb.coerceAtLeast(64).toLong() * 1024 * 1024),
                StandaloneDatabaseProvider(context),
            ).also { simpleCache = it }
        }

        fun isHlsUrl(url: String) = url.lowercase().let { it.contains(".m3u8") || it.contains("/m3u8") }

        private val softwarePreferredSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val all = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            val sw = all.filter { it.softwareOnly }
            if (sw.isEmpty()) all else sw + all.filterNot { it.softwareOnly }
        }
    }

    private val httpFactory by lazy {
        OkHttpDataSource.Factory(Http.client).setUserAgent(DEFAULT_UA)
    }

    private fun dataSourceFactory(local: Boolean): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        if (local) return upstream
        return CacheDataSource.Factory()
            .setCache(cache(context, settings.state.value.cacheSizeMb))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun buildMediaSource(url: String, mediaId: String, forceHls: Boolean = false): MediaSource {
        val local = url.startsWith("/") || url.startsWith("file:")
        val uri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)
        val hls = forceHls || isHlsUrl(url)
        val item = MediaItem.Builder().setUri(uri).setMediaId(mediaId)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        val dsf = dataSourceFactory(local)
        return if (hls) HlsMediaSource.Factory(dsf).setAllowChunklessPreparation(true).createMediaSource(item)
        else ProgressiveMediaSource.Factory(dsf).createMediaSource(item)
    }

    /** 获取（或按最新设置重建）播放器，必须在主线程调用 */
    fun obtain(): ExoPlayer {
        val s = settings.state.value
        val key = "${s.hwDecode}|${s.bufferSeconds}"
        player?.let { if (key == configKey) return it }
        player?.release()
        preparedKey = null
        val maxBuf = (s.bufferSeconds * 1000).coerceIn(5_000, 600_000)
        val minBuf = minOf(15_000, maxBuf)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10_000, true)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (s.hwDecode) MediaCodecSelector.DEFAULT else softwarePreferredSelector)
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(false)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player = p
        configKey = key
        return p
    }

    /** 预热：提前 prepare 但不播放，进入播放页即可秒开 */
    fun prewarm(key: String, url: String, startMs: Long) {
        if (inUse) return
        if (preparedKey == key && preparedUrl == url) return
        val p = obtain()
        p.playWhenReady = false
        p.setMediaSource(buildMediaSource(url, key), startMs.coerceAtLeast(0))
        p.prepare()
        preparedKey = key
        preparedUrl = url
    }

    fun markPrepared(key: String, url: String) {
        preparedKey = key
        preparedUrl = url
    }

    fun stop() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        preparedKey = null
        preparedUrl = null
    }

    fun releaseAll() {
        player?.release()
        player = null
        configKey = null
        preparedKey = null
    }
}

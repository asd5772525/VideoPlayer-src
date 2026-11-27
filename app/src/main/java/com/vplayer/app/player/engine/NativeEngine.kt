package com.vplayer.app.player.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vplayer.app.data.net.DEFAULT_UA
import com.vplayer.app.player.PlayerManager
import com.vplayer.app.player.ad.AdFilterResult
import com.vplayer.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.danmaku.ijk.media.player.AndroidMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File

/**
 * IJK（ijkplayer / FFmpeg，GSYVideoPlayer 发布在 Maven Central 的构建）和 Android 系统 MediaPlayer 两个内核。
 * 两者都实现 ijk 的 IMediaPlayer 接口，这里用 Media3 的 [SimpleBasePlayer] 包一层，对外就是一个普通的 [Player]：
 * PlayerView 画面、进度条、手势、倍速、进度记忆、自动下一集等代码不需要区分内核。
 *
 * 去广告：IJK 通过 [HlsLocalizer] 播放本地改写过的 m3u8；系统播放器不支持（本地 m3u8 无法交给系统 HLS 播放）。
 */
@OptIn(UnstableApi::class)
class NativeEngine(
    private val context: Context,
    override val kind: Kernel,
    private val profile: () -> PlaybackProfile,
    private val hwDecode: () -> Boolean,
    private val onAdFiltered: (String, AdFilterResult) -> Unit,
) : PlayerEngine {

    private val facade = Facade()
    override val player: Player get() = facade
    override val adFilterSupported: Boolean get() = kind == Kernel.IJK

    companion object {
        @Volatile
        private var ijkLoaded: Boolean? = null

        /** IJK 的 native 库能否加载（没有对应 ABI 的 .so 时为 false，自动退回系统播放器） */
        fun ijkAvailable(): Boolean = ijkLoaded ?: synchronized(this) {
            ijkLoaded ?: try {
                IjkMediaPlayer.loadLibrariesOnce(null)
                IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_ERROR)
                true
            } catch (t: Throwable) {
                AppLog.w("kernel", "IJK 内核不可用（${Build.SUPPORTED_ABIS.joinToString()}）：${t.message}")
                false
            }.also { ijkLoaded = it }
        }

        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_VIDEO_SURFACE,
        ).build()

        /** 告诉 PlayerView「有视频轨」：首帧出来前保持黑色遮罩，出来后再显示画面 */
        private val VIDEO_TRACKS = Tracks(
            listOf(Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
        )
        private const val IJK_WHITELIST = "async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data"
    }

    override suspend fun load(req: MediaRequest, startMs: Long) {
        var playUrl = req.url
        var local = false
        val rule = req.adRule
        if (kind == Kernel.IJK && rule != null && PlayerManager.isHlsUrl(req.url) && req.headers.isEmpty() && req.url.startsWith("http")) {
            val loc = withContext(Dispatchers.IO) {
                runCatching { HlsLocalizer.localize(req.url, rule, File(context.cacheDir, "ijk_m3u8")) }
                    .onFailure { AppLog.w("ad", "IJK 去广告改写失败，按原地址播放：${it.message}") }.getOrNull()
            }
            if (loc != null) {
                playUrl = loc.file.absolutePath
                local = true
                onAdFiltered(req.mediaId, loc.result)
            }
        }
        facade.open(req, playUrl, local, startMs.coerceAtLeast(0))
    }

    override fun downloadSpeedBps(): Long = (facade.mp as? IjkMediaPlayer)?.tcpSpeed ?: -1

    override fun release() = facade.release()

    private inner class Facade : SimpleBasePlayer(Looper.getMainLooper()) {
        var mp: IMediaPlayer? = null
        private var item: MediaItem? = null
        private var req: MediaRequest? = null
        private var playUrl = ""
        private var local = false
        private var pwr = false
        private var pstate = Player.STATE_IDLE
        private var err: PlaybackException? = null
        private var prepared = false
        private var durationMs = C.TIME_UNSET
        private var vsize = VideoSize.UNKNOWN
        private var speed = 1f
        private var bufferedPct = 0
        private var pendingSeek = 0L
        private var firstFrame = false
        private var surface: Surface? = null
        private var holder: SurfaceHolder? = null
        private var texture: TextureView? = null
        private var ownSurface: Surface? = null

        override fun getState(): State {
            val b = State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(pwr, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackParameters(PlaybackParameters(speed))
                .setVideoSize(vsize)
                .setPlayerError(err)
            val it = item
            if (it == null) {
                b.setPlaybackState(Player.STATE_IDLE)
            } else {
                val st = if (err != null) Player.STATE_IDLE else pstate
                b.setPlaylist(
                    listOf(
                        MediaItemData.Builder(it.mediaId.ifEmpty { "item" }).setMediaItem(it)
                            .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                            .setIsSeekable(durationMs > 0)
                            .setTracks(if (prepared) VIDEO_TRACKS else Tracks.EMPTY)
                            .build()
                    )
                )
                    .setCurrentMediaItemIndex(0)
                    .setPlaybackState(st)
                    .setIsLoading(st == Player.STATE_BUFFERING)
                    .setContentPositionMs { position() }
                    .setContentBufferedPositionMs { bufferedPosition() }
                    .setTotalBufferedDurationMs { (bufferedPosition() - position()).coerceAtLeast(0) }
                if (firstFrame) {
                    b.setNewlyRenderedFirstFrame(true)
                    firstFrame = false
                }
            }
            return b.build()
        }

        private fun position(): Long {
            val p = mp
            return if (prepared && p != null) runCatching { p.currentPosition }.getOrDefault(0L).coerceAtLeast(0) else pendingSeek
        }

        private fun bufferedPosition(): Long {
            val p = mp ?: return 0
            val pos = position()
            if (p is IjkMediaPlayer) return pos + runCatching { p.videoCachedDuration }.getOrDefault(0L).coerceAtLeast(0)
            return if (durationMs > 0) maxOf(pos, durationMs * bufferedPct / 100) else pos
        }

        fun open(r: MediaRequest, url: String, isLocal: Boolean, startMs: Long) {
            releasePlayer()
            req = r
            playUrl = url
            local = isLocal
            item = MediaItem.Builder().setUri(r.url).setMediaId(r.mediaId).build()
            err = null
            prepared = false
            durationMs = C.TIME_UNSET
            vsize = VideoSize.UNKNOWN
            bufferedPct = 0
            pendingSeek = startMs
            pstate = Player.STATE_BUFFERING
            val p = try { create(startMs) } catch (t: Throwable) {
                fail(PlaybackException("${kind.label} 初始化失败：${t.message}", t, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
                return
            }
            mp = p
            p.setOnPreparedListener { onPrepared() }
            p.setOnInfoListener { _, what, _ -> onInfo(what); true }
            p.setOnVideoSizeChangedListener { _, w, h, sarNum, sarDen ->
                vsize = VideoSize(w, h, if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f)
                invalidateState()
            }
            p.setOnBufferingUpdateListener { _, pct -> bufferedPct = pct }
            p.setOnCompletionListener { pstate = Player.STATE_ENDED; invalidateState() }
            p.setOnSeekCompleteListener { invalidateState() }
            p.setOnErrorListener { _, what, extra ->
                AppLog.w("kernel", "${kind.label} 错误 what=$what extra=$extra")
                fail(PlaybackException("${kind.label} 播放失败（what=$what extra=$extra）", null, KernelPolicy.nativeErrorCode(what, extra)))
                true
            }
            surface?.let { p.setSurface(it) }
            try {
                val headers = r.headers.filterKeys { it.isNotBlank() }
                if (p is IjkMediaPlayer) {
                    if (headers.isEmpty() || isLocal) p.setDataSource(url) else p.setDataSource(url, headers)
                } else {
                    val uri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)
                    p.setDataSource(context, uri, headers.ifEmpty { mapOf("User-Agent" to DEFAULT_UA) })
                }
                p.prepareAsync()
            } catch (t: Throwable) {
                fail(PlaybackException("${kind.label} 打开地址失败：${t.message}", t, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                return
            }
            invalidateState()
        }

        private fun create(startMs: Long): IMediaPlayer {
            if (kind == Kernel.SYSTEM) return AndroidMediaPlayer()
            val prof = profile()
            val p = IjkMediaPlayer()
            val P = IjkMediaPlayer.OPT_CATEGORY_PLAYER
            val F = IjkMediaPlayer.OPT_CATEGORY_FORMAT
            val C2 = IjkMediaPlayer.OPT_CATEGORY_CODEC
            val hw = if (hwDecode()) 1L else 0L
            p.setOption(P, "mediacodec", hw)
            p.setOption(P, "mediacodec-hevc", hw)
            p.setOption(P, "mediacodec-auto-rotate", 1L)
            p.setOption(P, "mediacodec-handle-resolution-change", 1L)
            p.setOption(P, "opensles", 0L)
            p.setOption(P, "framedrop", 1L)
            p.setOption(P, "soundtouch", 1L)          // 倍速不变调
            p.setOption(P, "start-on-prepared", 0L)   // 由 playWhenReady 控制
            p.setOption(P, "enable-accurate-seek", 0L)
            p.setOption(P, "packet-buffering", 1L)
            // 缓冲水位：首次起播 / 卡顿后恢复 / 上限，对应加载策略
            p.setOption(P, "first-high-water-mark-ms", prof.startMs.toLong())
            p.setOption(P, "next-high-water-mark-ms", prof.rebufferMs.toLong())
            p.setOption(P, "last-high-water-mark-ms", prof.minBufferMs.toLong())
            p.setOption(P, "max-buffer-size", (prof.maxBufferMs / 1000L * 512 * 1024).coerceIn(8L shl 20, 100L shl 20))
            if (startMs > 0) p.setOption(P, "seek-at-start", startMs)
            p.setOption(F, "reconnect", 1L)
            p.setOption(F, "timeout", prof.readTimeoutSec * 1_000_000L)
            p.setOption(F, "dns_cache_timeout", 600_000_000L)
            p.setOption(F, "fflags", "fastseek")
            p.setOption(F, "user_agent", req?.headers?.entries?.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_UA)
            when (prof.id) {
                "fast" -> { p.setOption(F, "analyzeduration", 1_000_000L); p.setOption(F, "probesize", 2L shl 20) }
                "balanced" -> p.setOption(F, "analyzeduration", 3_000_000L)
            }
            if (local) p.setOption(F, "protocol_whitelist", IJK_WHITELIST)
            p.setOption(C2, "skip_loop_filter", 48L)
            return p
        }

        private fun onPrepared() {
            prepared = true
            val p = mp ?: return
            durationMs = p.duration.takeIf { it > 0 } ?: C.TIME_UNSET
            // IJK 用 seek-at-start 起播；系统播放器在这里 seek
            if (kind == Kernel.SYSTEM && pendingSeek > 0) runCatching { p.seekTo(pendingSeek) }
            applySpeed()
            pstate = Player.STATE_READY
            if (pwr) runCatching { p.start() }
            invalidateState()
        }

        private fun onInfo(what: Int) {
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> pstate = Player.STATE_BUFFERING
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> if (prepared) pstate = Player.STATE_READY
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> { firstFrame = true; if (prepared) pstate = Player.STATE_READY }
                else -> return
            }
            invalidateState()
        }

        private fun fail(e: PlaybackException) {
            err = e
            pstate = Player.STATE_IDLE
            invalidateState()
        }

        private fun applySpeed() {
            val p = mp ?: return
            runCatching {
                when (p) {
                    is IjkMediaPlayer -> p.setSpeed(speed)
                    is AndroidMediaPlayer -> if (Build.VERSION.SDK_INT >= 23 && prepared) {
                        val mpi = p.internalMediaPlayer
                        val wasPlaying = mpi.isPlaying
                        mpi.playbackParams = mpi.playbackParams.setSpeed(speed)
                        if (!wasPlaying || !pwr) mpi.pause() // 设置倍速会让系统播放器自动开始播放
                    }
                }
            }
        }

        private fun releasePlayer() {
            mp?.let { p ->
                runCatching { p.setOnErrorListener(null); p.setOnInfoListener(null); p.setOnPreparedListener(null) }
                runCatching { p.stop() }
                runCatching { p.release() }
            }
            mp = null
            prepared = false
        }

        // ---------- Player 接口 ----------
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            pwr = playWhenReady
            val p = mp
            if (p != null && prepared) runCatching {
                if (playWhenReady) {
                    if (pstate == Player.STATE_ENDED) { p.seekTo(0); pstate = Player.STATE_READY }
                    p.start()
                } else p.pause()
            }
            return Futures.immediateVoidFuture()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            val r = req
            if (r != null && (mp == null || err != null)) open(r, playUrl, local, position())
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            pendingSeek = position()
            releasePlayer()
            err = null
            pstate = Player.STATE_IDLE
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> {
            releasePlayer()
            clearOutput()
            item = null
            pstate = Player.STATE_IDLE
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            val pos = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0)
            val p = mp
            if (p != null && prepared) {
                runCatching { p.seekTo(pos) }
                if (pstate == Player.STATE_ENDED) pstate = Player.STATE_READY
            } else pendingSeek = pos
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            speed = playbackParameters.speed
            applySpeed()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
            clearOutput()
            when (videoOutput) {
                is SurfaceView -> attachHolder(videoOutput.holder)
                is SurfaceHolder -> attachHolder(videoOutput)
                is Surface -> setSurfaceInternal(videoOutput)
                is TextureView -> attachTexture(videoOutput)
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
            clearOutput()
            return Futures.immediateVoidFuture()
        }

        private val holderCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = setSurfaceInternal(h.surface)
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) = setSurfaceInternal(null)
        }

        private val textureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                ownSurface?.release()
                ownSurface = Surface(st).also { setSurfaceInternal(it) }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                setSurfaceInternal(null); ownSurface?.release(); ownSurface = null; return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        private fun attachHolder(h: SurfaceHolder) {
            holder = h
            h.addCallback(holderCallback)
            setSurfaceInternal(h.surface?.takeIf { it.isValid })
        }

        private fun attachTexture(tv: TextureView) {
            texture = tv
            tv.surfaceTextureListener = textureListener
            tv.surfaceTexture?.takeIf { tv.isAvailable }?.let { textureListener.onSurfaceTextureAvailable(it, tv.width, tv.height) }
        }

        private fun clearOutput() {
            holder?.removeCallback(holderCallback)
            holder = null
            texture?.surfaceTextureListener = null
            texture = null
            ownSurface?.release()
            ownSurface = null
            setSurfaceInternal(null)
        }

        private fun setSurfaceInternal(s: Surface?) {
            surface = s
            runCatching { mp?.setSurface(s) }
        }
    }
}

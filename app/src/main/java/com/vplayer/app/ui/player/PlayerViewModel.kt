package com.vplayer.app.ui.player

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vplayer.app.AppContainer
import com.vplayer.app.data.db.HistoryEntity
import com.vplayer.app.data.db.ProgressEntity
import com.vplayer.app.data.db.ShowPrefEntity
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import com.vplayer.app.data.model.vodKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val vod: Vod? = null,
    val line: Int = 0,
    val ep: Int = 0,
    val title: String = "",
    val epName: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val resumeAt: Long = 0,
    val showResume: Boolean = false,
    val speed: Float = 1f,
    val resizeMode: Int = 0,
    val introSec: Int = 0,
    val outroSec: Int = 0,
    val autoNext: Boolean = true,
    val longPressSpeed: Float = 3f,
    val isLocal: Boolean = false,
    val isDirect: Boolean = true,
)

/**
 * 播放页逻辑：
 * - 复用详情页预热好的播放器（preparedKey 命中即直接起播）
 * - 每 5 秒、暂停、切集、退到后台、退出时保存每一集的进度；进入时按集恢复并提示
 * - 播放列表预加载下一集 / 自动下一集 / 跳过片头片尾
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    val c: AppContainer,
    private val sourceId: String,
    private val vodId: String,
    private val startLine: Int,
    private val startEp: Int,
    private val localPath: String?,
    private val localTitle: String?,
) : ViewModel() {

    val key = vodKey(sourceId, vodId)
    val player: ExoPlayer = c.player.obtain()
    private val _state = MutableStateFlow(PlayerUiState(title = localTitle ?: ""))
    val state: StateFlow<PlayerUiState> = _state

    private var source: Source? = null
    private var forceHls = false
    private var currentUrl: String? = null
    private var nextPreloaded = false
    private var outroTriggered = false
    private var lastPos = 0L
    private var lastDur = 0L
    private var playJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.update { it.copy(loading = false, error = null) }
                    maybePreloadNext()
                }
                Player.STATE_BUFFERING -> _state.update { it.copy(loading = true) }
                Player.STATE_ENDED -> onEnded()
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return
            val newEp = mediaItem?.mediaId?.substringAfterLast('|')?.toIntOrNull() ?: return
            val st = _state.value
            if (newEp == st.ep) return
            // 上一集看完
            saveProgressFor(st.ep, st.epName, lastDur, lastDur)
            val name = st.vod?.lines?.getOrNull(st.line)?.episodes?.getOrNull(newEp)?.name ?: ""
            _state.update { it.copy(ep = newEp, epName = name, showResume = false) }
            nextPreloaded = false
            outroTriggered = false
            mediaItem.mediaId.let { c.player.markPrepared(it, currentUrl ?: "") }
            val idx = player.currentMediaItemIndex
            if (idx > 0) player.removeMediaItems(0, idx)
            val intro = st.introSec
            if (intro > 0 && player.currentPosition < intro * 1000L) player.seekTo(intro * 1000L)
        }

        override fun onPlayerError(error: PlaybackException) {
            val parsing = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            val url = currentUrl
            if (parsing && !forceHls && url != null) {
                // 地址没有扩展名但实际是 HLS：改用 HLS 重试一次
                forceHls = true
                val pos = player.currentPosition
                player.setMediaSource(c.player.buildMediaSource(url, "$key|${_state.value.line}|${_state.value.ep}", forceHls = true), pos)
                player.prepare()
                player.play()
                return
            }
            _state.update { it.copy(loading = false, error = "播放失败（${error.errorCodeName}），可重试或切换线路") }
        }
    }

    init {
        c.player.inUse = true
        player.addListener(listener)
        viewModelScope.launch {
            val s = c.settings.current()
            val pref = c.db.playbackDao().showPref(key)
            _state.update {
                it.copy(
                    speed = s.defaultSpeed, autoNext = s.autoNext, longPressSpeed = s.longPressSpeed,
                    introSec = pref?.introSec ?: s.skipIntro, outroSec = pref?.outroSec ?: s.skipOutro,
                )
            }
            source = c.sources.byId(sourceId)
            val vod = source?.let { src -> runCatching { c.cms.detail(src, vodId) }.getOrNull() }
            _state.update { it.copy(vod = vod, title = vod?.name ?: localTitle ?: "") }
            if (vod == null && localPath == null) {
                _state.update { it.copy(loading = false, error = "加载影片信息失败，请检查网络或数据源") }
                return@launch
            }
            playEpisode(startLine, startEp)
            ticker()
        }
    }

    private suspend fun ticker() {
        var n = 0
        while (viewModelScope.isActive) {
            delay(500)
            val dur = player.duration
            val pos = player.currentPosition
            if (dur > 0 && dur != C.TIME_UNSET) { lastDur = dur; lastPos = pos }
            if (++n % 10 == 0 && player.isPlaying) saveProgress() // 每 5 秒保存
            // 跳过片尾
            val outro = _state.value.outroSec
            if (outro > 0 && dur > 0 && player.isPlaying && !outroTriggered && pos >= dur - outro * 1000L) {
                outroTriggered = true
                if (_state.value.autoNext && hasNext()) {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else next()
                } else player.pause()
            }
        }
    }

    fun hasNext(): Boolean {
        val st = _state.value
        val eps = st.vod?.lines?.getOrNull(st.line)?.episodes ?: return false
        return st.ep + 1 < eps.size
    }

    fun next() {
        val st = _state.value
        if (hasNext()) playEpisode(st.line, st.ep + 1)
    }

    fun playEpisode(line: Int, ep: Int, fromStart: Boolean = false) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val prev = _state.value
            if (prev.vod != null && currentUrl != null) saveProgress()
            val vod = prev.vod
            val episode = vod?.lines?.getOrNull(line)?.episodes?.getOrNull(ep)
            val mediaKey = "$key|$line|$ep"
            _state.update {
                it.copy(line = line, ep = ep, epName = episode?.name ?: "", loading = true, error = null, showResume = false)
            }
            nextPreloaded = false
            outroTriggered = false
            forceHls = false
            val s = c.settings.current()
            val saved = if (!fromStart && s.rememberProgress) c.db.playbackDao().progress(key, ep) else null
            val savedPos = if (saved != null && saved.duration > 0 && saved.position in 5_000 until saved.duration - 10_000) saved.position else 0L
            var startPos = savedPos
            val intro = _state.value.introSec
            if (startPos == 0L && intro > 0) startPos = intro * 1000L

            player.pauseAtEndOfMediaItems = !_state.value.autoNext
            if (c.player.preparedKey == mediaKey && player.mediaItemCount > 0) {
                // 详情页已预热：直接起播
                currentUrl = c.player.preparedUrl
                if (fromStart) player.seekTo(0)
                else if (kotlin.math.abs(player.currentPosition - startPos) > 3000) player.seekTo(startPos)
                player.playWhenReady = true
            } else {
                val local = localPath?.takeIf { ep == startEp && line == startLine }
                    ?: c.downloads.completedFile(key, ep)
                val url = local ?: try {
                    c.resolver.resolve(episode?.url ?: "")
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, error = "解析失败：${e.message}") }
                    return@launch
                }
                if (url.isBlank()) {
                    _state.update { it.copy(loading = false, error = "没有可用的播放地址") }
                    return@launch
                }
                currentUrl = url
                _state.update { it.copy(isLocal = local != null, isDirect = local != null || url == episode?.url) }
                player.setMediaSource(c.player.buildMediaSource(url, mediaKey), startPos)
                player.prepare()
                player.playWhenReady = true
                c.player.markPrepared(mediaKey, url)
            }
            player.setPlaybackSpeed(_state.value.speed)
            if (savedPos > 0) _state.update { it.copy(resumeAt = savedPos, showResume = true) }
            saveHistory(line, ep, savedPos, saved?.duration ?: 0)
        }
    }

    /** 预加载下一集：解析后加入播放列表，ExoPlayer 会在当前集缓冲完后提前缓冲下一集 */
    private fun maybePreloadNext() {
        if (nextPreloaded || !hasNext()) return
        val s = c.settings.state.value
        if (!s.preloadNext || !_state.value.autoNext) return
        nextPreloaded = true
        val st = _state.value
        val nextEp = st.ep + 1
        val ep = st.vod?.lines?.getOrNull(st.line)?.episodes?.getOrNull(nextEp) ?: return
        viewModelScope.launch {
            val url = c.downloads.completedFile(key, nextEp)
                ?: runCatching { c.resolver.resolve(ep.url) }.getOrNull() ?: return@launch
            if (_state.value.ep != st.ep || _state.value.line != st.line) return@launch
            if (player.mediaItemCount > player.currentMediaItemIndex + 1) {
                player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
            }
            player.addMediaSource(c.player.buildMediaSource(url, "$key|${st.line}|$nextEp"))
        }
    }

    private fun onEnded() {
        saveProgressFor(_state.value.ep, _state.value.epName, lastDur, lastDur)
        if (_state.value.autoNext && hasNext()) next()
    }

    fun restartFromBeginning() {
        player.seekTo(0)
        _state.update { it.copy(showResume = false) }
    }

    fun hideResume() = _state.update { it.copy(showResume = false) }

    fun setSpeed(v: Float) {
        player.setPlaybackSpeed(v)
        _state.update { it.copy(speed = v) }
    }

    fun setResizeMode(m: Int) = _state.update { it.copy(resizeMode = m) }

    fun setAutoNext(v: Boolean) {
        _state.update { it.copy(autoNext = v) }
        player.pauseAtEndOfMediaItems = !v
        viewModelScope.launch { c.settings.setAutoNext(v) }
    }

    fun setLongPressSpeed(v: Float) {
        _state.update { it.copy(longPressSpeed = v) }
        viewModelScope.launch { c.settings.setLongPressSpeed(v) }
    }

    fun setSkip(intro: Int, outro: Int) {
        _state.update { it.copy(introSec = intro, outroSec = outro) }
        viewModelScope.launch { c.db.playbackDao().saveShowPref(ShowPrefEntity(key, intro, outro)) }
    }

    fun retry() {
        val st = _state.value
        c.player.stop()
        playEpisode(st.line, st.ep)
    }

    /** 保存当前集进度（主线程读取播放器，写库放在 appScope，退出页面也能完成） */
    fun saveProgress() {
        val dur = player.duration
        val pos = player.currentPosition
        if (dur <= 0 || dur == C.TIME_UNSET || currentUrl == null) return
        val st = _state.value
        val playingEp = player.currentMediaItem?.mediaId?.substringAfterLast('|')?.toIntOrNull() ?: st.ep
        if (playingEp != st.ep) return
        saveProgressFor(st.ep, st.epName, pos, dur)
    }

    private fun saveProgressFor(ep: Int, epName: String, pos: Long, dur: Long) {
        if (dur <= 0) return
        val st = _state.value
        val now = System.currentTimeMillis()
        val epCount = st.vod?.lines?.getOrNull(st.line)?.episodes?.size ?: 1
        c.appScope.launch {
            if (!c.settings.current().rememberProgress) return@launch
            val dao = c.db.playbackDao()
            dao.saveProgress(ProgressEntity(key, ep, epName, pos, dur, now))
            dao.saveHistory(
                HistoryEntity(
                    vodKey = key, sourceId = sourceId, vodId = vodId, name = st.title, pic = st.vod?.pic ?: "",
                    lineIndex = st.line, epIndex = ep, epName = epName, epCount = epCount,
                    position = pos, duration = dur, updatedAt = now,
                )
            )
        }
    }

    private fun saveHistory(line: Int, ep: Int, pos: Long, dur: Long) {
        val st = _state.value
        val epCount = st.vod?.lines?.getOrNull(line)?.episodes?.size ?: 1
        val now = System.currentTimeMillis()
        c.appScope.launch {
            if (!c.settings.current().rememberProgress) return@launch
            c.db.playbackDao().saveHistory(
                HistoryEntity(key, sourceId, vodId, st.title, st.vod?.pic ?: "", line, ep, st.epName, epCount, pos, dur, now)
            )
        }
    }

    override fun onCleared() {
        saveProgress()
        player.removeListener(listener)
        c.player.inUse = false
        c.player.stop()
    }
}

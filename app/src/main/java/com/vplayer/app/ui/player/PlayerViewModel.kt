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
import com.vplayer.app.player.AdRules
import com.vplayer.app.player.Failover
import com.vplayer.app.player.HoldSeek
import com.vplayer.app.player.Resolved
import com.vplayer.app.player.ad.AdRule
import com.vplayer.app.util.AppLog
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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
    val parseName: String = "",
    /** 长按提示：「快进 3x ▶▶」/「◀◀ 快退 3x」，null = 没在长按 */
    val holdLabel: String? = null,
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
    private var currentResolved: Resolved? = null

    /** 给播放页弹 Toast 的一次性消息 */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // ---- 自动换线 ----
    private val triedLines = mutableSetOf<Int>()
    private val triedParses = mutableSetOf<String>()
    private var failingOver = false
    private var stallMs = 0L
    private val adNotified = mutableSetOf<String>()

    // ---- 长按快进 / 快退 ----
    private var holdJob: Job? = null
    private var holdRewind = false
    private var holdWasPlaying = false
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
                    stallMs = 0
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
                player.setMediaSource(
                    c.player.buildMediaSource(
                        url, "$key|${_state.value.line}|${_state.value.ep}", forceHls = true,
                        headers = currentResolved?.headers.orEmpty(), adRule = if (_state.value.isLocal) null else adRule(),
                    ),
                    pos,
                )
                player.prepare()
                player.play()
                return
            }
            AppLog.e("player", "播放失败 ${error.errorCodeName} line=${_state.value.line} ep=${_state.value.ep} url=${url?.take(120)}", error)
            if (!failover("播放失败（${error.errorCodeName}）")) {
                _state.update { it.copy(loading = false, error = "播放失败（${error.errorCodeName}），可重试或切换线路") }
            }
        }
    }

    init {
        c.player.inUse = true
        player.addListener(listener)
        c.player.adListener = { mediaId, r ->
            if (mediaId.startsWith("$key|") && adNotified.add(mediaId) && mediaId == "$key|${_state.value.line}|${_state.value.ep}") {
                toasts.tryEmit("已去除插播广告 ${r.removedSegments} 段（约 ${r.removedSeconds.toInt()} 秒）")
            }
        }
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
            // 卡顿检测：想播但一直在缓冲超过 8 秒 → 自动换线 / 换解析
            val st0 = _state.value
            val stalled = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady &&
                holdJob == null && currentUrl != null && !st0.isLocal && st0.error == null
            stallMs = if (stalled) stallMs + 500 else 0
            if (stallMs >= HoldSeek.STALL_FAILOVER_MS) {
                stallMs = 0
                AppLog.w("player", "卡顿超过 8 秒 line=${st0.line} ep=${st0.ep}")
                failover("卡顿超过 8 秒")
            }
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

    /**
     * @param startAtMs 指定起播位置（自动换线时保持进度）
     * @param auto true = 自动换线触发，不清空“已尝试”记录
     */
    fun playEpisode(line: Int, ep: Int, fromStart: Boolean = false, startAtMs: Long? = null, auto: Boolean = false) {
        if (!auto) { triedLines.clear(); triedParses.clear() }
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
            currentResolved = null
            val s = c.settings.current()
            val saved = if (!fromStart && s.rememberProgress) c.db.playbackDao().progress(key, ep) else null
            val savedPos = if (saved != null && saved.duration > 0 && saved.position in 5_000 until saved.duration - 10_000) saved.position else 0L
            var startPos = startAtMs ?: savedPos
            val intro = _state.value.introSec
            if (startPos == 0L && intro > 0) startPos = intro * 1000L
            stallMs = 0

            player.pauseAtEndOfMediaItems = !_state.value.autoNext
            if (!auto && c.player.preparedKey == mediaKey && player.mediaItemCount > 0) {
                // 详情页已预热：直接起播
                currentUrl = c.player.preparedUrl
                currentResolved = episode?.url?.let { c.resolver.cachedResolved(it) } ?: currentUrl?.let { Resolved(it) }
                _state.update { it.copy(isDirect = currentResolved?.parse == null, parseName = currentResolved?.parse?.name ?: "") }
                if (fromStart) player.seekTo(0)
                else if (kotlin.math.abs(player.currentPosition - startPos) > 3000) player.seekTo(startPos)
                player.playWhenReady = true
            } else {
                val local = localPath?.takeIf { ep == startEp && line == startLine }
                    ?: c.downloads.completedFile(key, ep)
                val lineName = vod?.lines?.getOrNull(line)?.name
                val resolved = if (local != null) Resolved(local) else try {
                    c.resolver.resolveFull(episode?.url ?: "", source, lineName, exclude = triedParses)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("parse", "解析失败 line=$line ep=$ep：${e.message}")
                    if (!failover("解析失败")) _state.update { it.copy(loading = false, error = "解析失败：${e.message}") }
                    return@launch
                }
                val url = resolved.url
                if (url.isBlank()) {
                    if (!failover("没有可用的播放地址")) _state.update { it.copy(loading = false, error = "没有可用的播放地址") }
                    return@launch
                }
                currentUrl = url
                currentResolved = resolved
                _state.update {
                    it.copy(isLocal = local != null, isDirect = local != null || resolved.parse == null, parseName = resolved.parse?.name ?: "")
                }
                player.setMediaSource(
                    c.player.buildMediaSource(url, mediaKey, headers = resolved.headers, adRule = if (local != null) null else adRule()),
                    startPos,
                )
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
            val local = c.downloads.completedFile(key, nextEp)
            val r = if (local != null) Resolved(local)
            else runCatching { c.resolver.resolveFull(ep.url, source, st.vod.lines.getOrNull(st.line)?.name) }.getOrNull() ?: return@launch
            if (_state.value.ep != st.ep || _state.value.line != st.line) return@launch
            if (player.mediaItemCount > player.currentMediaItemIndex + 1) {
                player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
            }
            player.addMediaSource(
                c.player.buildMediaSource(r.url, "$key|${st.line}|$nextEp", headers = r.headers, adRule = if (local != null) null else adRule())
            )
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
        st.vod?.lines?.getOrNull(st.line)?.episodes?.getOrNull(st.ep)?.url?.let { c.resolver.invalidate(it) }
        playEpisode(st.line, st.ep, startAtMs = lastPos.takeIf { it > 0 })
    }

    private fun adRule(): AdRule? = AdRules.effective(source?.adFilter, c.settings.state.value.adFilter)

    /**
     * 自动换线：先换同一线路的下一个解析接口（当前地址来自解析时），再换下一条线路；保持集数与进度。
     * @return false = 没有可换的（或已关闭自动换线），由调用方显示错误
     */
    private fun failover(reason: String): Boolean {
        val st = _state.value
        val vod = st.vod ?: return false
        if (st.isLocal || failingOver || !c.settings.state.value.autoFailover) return false
        val lines = vod.lines
        val pos = player.currentPosition.takeIf { it > 0 } ?: lastPos
        val curName = lines.getOrNull(st.line)?.name ?: "线路${st.line + 1}"
        val raw = lines.getOrNull(st.line)?.episodes?.getOrNull(st.ep)?.url ?: return false
        failingOver = true
        triedLines += st.line
        currentResolved?.parse?.let { triedParses += it.id }
        viewModelScope.launch {
            try {
                // 1) 当前地址来自解析接口：换下一个解析
                if (currentResolved?.parse != null) {
                    val next = runCatching { c.resolver.resolveFull(raw, source, curName, exclude = triedParses, allowDirect = false) }.getOrNull()
                    if (next != null && next.parse != null && next.url.isNotBlank()) {
                        AppLog.i("failover", "$reason → 换解析 ${next.parse.name}")
                        toasts.tryEmit("$reason，已切换解析「${next.parse.name}」")
                        switchTo(st.line, st.ep, next, pos)
                        return@launch
                    }
                }
                // 2) 换下一条线路
                val target = Failover.nextLine(lines, st.line, st.ep, triedLines)
                if (target == null) {
                    AppLog.w("failover", "$reason，所有线路都试过了")
                    _state.update { it.copy(loading = false, error = "$reason，所有线路都无法播放，可重试或稍后再看") }
                    return@launch
                }
                triedParses.clear()
                val name = lines[target.first].name
                AppLog.i("failover", "$reason → 换线路 $curName → $name")
                toasts.tryEmit("$reason，已自动切换到「$name」")
                c.resolver.invalidate(raw)
                playEpisode(target.first, target.second, startAtMs = pos, auto = true)
            } finally {
                failingOver = false
            }
        }
        return true
    }

    private fun switchTo(line: Int, ep: Int, r: Resolved, pos: Long) {
        val mediaKey = "$key|$line|$ep"
        currentUrl = r.url
        currentResolved = r
        forceHls = false
        nextPreloaded = false
        stallMs = 0
        _state.update { it.copy(loading = true, error = null, isDirect = r.parse == null, parseName = r.parse?.name ?: "") }
        player.setMediaSource(c.player.buildMediaSource(r.url, mediaKey, headers = r.headers, adRule = adRule()), pos)
        player.prepare()
        player.playWhenReady = true
        player.setPlaybackSpeed(_state.value.speed)
        c.player.markPrepared(mediaKey, r.url)
    }

    // ---- 手势 ----

    fun seekBy(deltaMs: Long) {
        val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, dur))
    }

    /** 长按开始：rewind=true 左半屏连续快退，false 右半屏倍速快进 */
    fun startHold(rewind: Boolean) {
        if (holdJob != null) return
        val speed = _state.value.longPressSpeed
        holdRewind = rewind
        holdWasPlaying = player.playWhenReady
        _state.update { it.copy(holdLabel = HoldSeek.label(rewind, speed)) }
        if (!rewind) {
            player.setPlaybackSpeed(speed)
            holdJob = viewModelScope.launch { kotlinx.coroutines.awaitCancellation() }
            return
        }
        // ExoPlayer 不能倒放：暂停后每 200ms 往回跳一段（关键帧附近，seek 更快）
        player.pause()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        holdJob = viewModelScope.launch {
            var target = player.currentPosition
            while (isActive) {
                target = HoldSeek.nextRewindPos(target, speed)
                player.seekTo(target)
                if (target == 0L) break
                delay(HoldSeek.TICK_MS)
            }
        }
    }

    fun endHold() {
        val job = holdJob ?: return
        job.cancel()
        holdJob = null
        if (holdRewind) {
            player.setSeekParameters(SeekParameters.DEFAULT)
            if (holdWasPlaying) player.play()
        } else player.setPlaybackSpeed(_state.value.speed)
        _state.update { it.copy(holdLabel = null) }
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
        holdJob?.cancel()
        c.player.adListener = null
        saveProgress()
        player.removeListener(listener)
        c.player.inUse = false
        c.player.stop()
    }
}

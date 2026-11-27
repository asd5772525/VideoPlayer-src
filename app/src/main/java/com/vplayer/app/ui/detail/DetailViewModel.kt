package com.vplayer.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vplayer.app.AppContainer
import com.vplayer.app.data.db.FavoriteEntity
import com.vplayer.app.data.db.HistoryEntity
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import com.vplayer.app.data.model.vodKey
import com.vplayer.app.player.SpeedTester
import com.vplayer.app.player.AdRules
import com.vplayer.app.player.Resolved
import com.vplayer.app.player.UrlResolver
import com.vplayer.app.player.ParseOrder
import com.vplayer.app.player.engine.Kernel
import com.vplayer.app.player.engine.KernelPolicy
import com.vplayer.app.player.engine.PlaybackProfiles
import com.vplayer.app.data.net.Warmup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailViewModel(private val c: AppContainer, private val sourceId: String, private val vodId: String) : ViewModel() {
    val key = vodKey(sourceId, vodId)
    private val _source = MutableStateFlow<Source?>(null)
    val source: StateFlow<Source?> = _source
    private val _vod = MutableStateFlow<Vod?>(null)
    val vod: StateFlow<Vod?> = _vod
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 当前线路（原始下标） */
    private val _line = MutableStateFlow(-1)
    val line: StateFlow<Int> = _line
    private var userPickedLine = false

    /** 线路测速：原始下标 -> ms（null 测速中，-1 超时） */
    private val _latency = MutableStateFlow<Map<Int, Long?>>(emptyMap())
    val latency: StateFlow<Map<Int, Long?>> = _latency

    val reverse = MutableStateFlow(false)

    val history: StateFlow<HistoryEntity?> = c.db.playbackDao().observeHistory(key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val watched: StateFlow<Set<Int>> = c.db.playbackDao().observeProgress(key).map { l -> l.map { it.epIndex }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val isFav: StateFlow<Boolean> = c.db.favoriteDao().observeIsFav(key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val downloaded: StateFlow<Set<Int>> = c.db.downloadDao().observeCompletedFor(key).map { l -> l.map { it.epIndex }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch {
            val s = c.sources.byId(sourceId)
            _source.value = s
            if (s == null) { _error.value = "数据源不存在或已被移除"; return@launch }
            // 预热这个源会用到的前两个解析接口的连接（详情页加载的同时完成握手）
            launch {
                if (PlaybackProfiles.of(c.settings.current()).dnsPrefetch)
                    runCatching { Warmup.warm(ParseOrder.forSource(c.sources.parses.value.map { it.parse }, s, null).take(2).map { it.url }) }
            }
            c.cms.cachedDetail(s, vodId)?.let { onVod(it) } // 预取命中：瞬间显示
            c.cms.detailFlow(s, vodId)
                .catch { e -> if (_vod.value == null) _error.value = e.message ?: "加载失败" }
                .collect { onVod(it) }
        }
    }

    private var tested = false
    private var prewarmed = false

    private fun onVod(v: Vod) {
        val first = _vod.value == null
        _vod.value = v
        _error.value = null
        if (_line.value !in v.lines.indices) {
            viewModelScope.launch {
                val h = c.db.playbackDao().history(key)
                _line.value = h?.lineIndex?.takeIf { it in v.lines.indices } ?: 0
                if (first) prewarm()
            }
        } else if (first) viewModelScope.launch { prewarm() }
        if (!tested && v.lines.size > 0) {
            tested = true
            viewModelScope.launch { testLines(v) }
        }
        // 收藏：同步最新集数
        viewModelScope.launch {
            c.db.favoriteDao().get(key)?.let { f ->
                val n = v.lines.maxOfOrNull { it.episodes.size } ?: 0
                if (n != f.latestEpCount) c.db.favoriteDao().upsert(f.copy(latestEpCount = n, remarks = v.remarks))
            }
        }
    }

    /** 并发测速所有线路，开启“自动选择最快线路”且没有观看记录时自动切换 */
    fun testLines(v0: Vod? = null) {
        val v = v0 ?: _vod.value ?: return
        viewModelScope.launch {
            _latency.value = v.lines.indices.associateWith { null }
            val epIdx = history.value?.epIndex ?: 0
            val results = v.lines.mapIndexed { i, l ->
                async { i to SpeedTester.ttfb((l.episodes.getOrNull(epIdx) ?: l.episodes.first()).url) }
            }.awaitAll()
            _latency.value = results.toMap()
            val s = c.settings.current()
            val hasHistory = c.db.playbackDao().history(key) != null
            if (s.autoSpeedTest && !userPickedLine && !hasHistory) {
                val best = results.filter { it.second >= 0 }.minByOrNull { it.second }?.first
                if (best != null && best != _line.value) {
                    _line.value = best
                    prewarmed = false
                    prewarm()
                }
            }
        }
    }

    fun selectLine(i: Int) {
        userPickedLine = true
        _line.value = i
        prewarmed = false
        viewModelScope.launch { prewarm() }
    }

    /** 续播目标：(线路, 集, 起始位置) */
    suspend fun resumeTarget(): Triple<Int, Int, Long> {
        val v = _vod.value ?: return Triple(0, 0, 0)
        val line = _line.value.coerceIn(0, (v.lines.size - 1).coerceAtLeast(0))
        val h = c.db.playbackDao().history(key)
        val ep = h?.epIndex?.takeIf { it in (v.lines.getOrNull(line)?.episodes?.indices ?: IntRange.EMPTY) } ?: 0
        val p = c.db.playbackDao().progress(key, ep)
        val pos = if (p != null && p.duration > 0 && p.position < p.duration - 10_000) p.position else 0L
        return Triple(line, ep, pos)
    }

    /** 详情页一打开就为续播集预热 ExoPlayer（解析地址 + prepare），进入播放页即可秒开 */
    private suspend fun prewarm() {
        if (prewarmed) return
        val v = _vod.value ?: return
        if (v.lines.isEmpty()) return
        prewarmed = true
        val (line, ep, pos) = resumeTarget()
        val e = v.lines.getOrNull(line)?.episodes?.getOrNull(ep) ?: return
        val local = c.downloads.completedFile(key, ep)
        val src = _source.value
        val r = if (local != null) Resolved(local)
        else runCatching { c.resolver.resolveFull(e.url, src, v.lines.getOrNull(line)?.name) }.getOrNull() ?: return
        val url = r.url
        val adRule = if (local != null) null else AdRules.effective(src?.adFilter, c.settings.current().adFilter)
        var startPos = pos
        if (startPos == 0L) {
            val intro = c.db.playbackDao().showPref(key)?.introSec ?: c.settings.current().skipIntro
            startPos = intro * 1000L
        }
        // 只有 ExoPlayer 能预热（prepare 但不播放）；其它内核只提前解析好地址（已缓存），进播放页直接用
        val user = Kernel.of(c.settings.current().kernel)
        val kernel = if (local != null) Kernel.EXO else KernelPolicy.initial(user, src?.kernel, ijkOk = true)
        if (kernel != Kernel.EXO) return
        withContext(Dispatchers.Main) { c.player.prewarm("$key|$line|$ep", url, startPos, r.headers, adRule) }
    }

    fun toggleFav() = viewModelScope.launch {
        val v = _vod.value ?: return@launch
        if (isFav.value) c.db.favoriteDao().delete(key)
        else {
            val n = v.lines.maxOfOrNull { it.episodes.size } ?: 0
            c.db.favoriteDao().upsert(
                FavoriteEntity(key, sourceId, vodId, v.name, v.pic, v.remarks, n, n, false, System.currentTimeMillis())
            )
        }
    }

    fun download(lineIndex: Int, eps: List<Int>) = viewModelScope.launch {
        val s = _source.value ?: return@launch
        val v = _vod.value ?: return@launch
        c.downloads.enqueue(s, v, lineIndex, eps)
    }

    override fun onCleared() {
        // 用户没有进入播放页就离开：停止预热，释放带宽
        if (!c.player.inUse && c.player.preparedKey?.startsWith("$key|") == true) c.player.stop()
    }

}

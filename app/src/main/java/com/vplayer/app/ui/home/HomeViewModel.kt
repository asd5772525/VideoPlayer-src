package com.vplayer.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.vplayer.app.AppContainer
import com.vplayer.app.data.cms.SourceItem
import com.vplayer.app.data.cms.vodPager
import com.vplayer.app.data.config.Notice
import com.vplayer.app.data.db.HistoryEntity
import com.vplayer.app.data.model.Category
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeFeed(val loading: Boolean = true, val banner: List<Vod> = emptyList(), val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(val c: AppContainer) : ViewModel() {
    val sources: StateFlow<List<SourceItem>> = c.sources.sources
    val current: StateFlow<Source?> = c.sources.current
    val healthRunning = c.health.running
    private val refresh = MutableStateFlow(0)

    private val sourceAndRefresh = combine(current.map { it }.distinctUntilChanged(), refresh) { s, r -> s to r }

    val categories: StateFlow<List<Category>> = current.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else c.cms.categories(s).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val feed: StateFlow<HomeFeed> = sourceAndRefresh.flatMapLatest { (s, _) ->
        if (s == null) flowOf(HomeFeed(loading = false, error = "NO_SOURCE"))
        else c.cms.pageFlow(s, 1)
            .map { HomeFeed(loading = false, banner = it.list.filter { v -> v.pic.isNotBlank() }.take(6)) }
            .onStart { emit(HomeFeed(loading = true)) }
            .catch { emit(HomeFeed(loading = false, error = it.message ?: "加载失败")) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeFeed())

    val continueWatching: StateFlow<List<HistoryEntity>> = c.db.playbackDao().observeHistoryList(20)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val notice: StateFlow<Notice?> = c.settings.state.map { s ->
        val n = c.config.notice
        if (n.enabled && n.text.isNotBlank() && s.dismissedNotice != n.id + n.text.hashCode()) n else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val pagers = mutableMapOf<String, Flow<PagingData<Vod>>>()

    /** 每个 (源, 分类) 一个缓存的 Pager；typeId=null 表示“最新” */
    fun pager(source: Source, typeId: String?): Flow<PagingData<Vod>> =
        pagers.getOrPut("${source.id}|$typeId|${refresh.value}") {
            vodPager(c.cms, source, typeId, null, c.appScope).cachedIn(viewModelScope)
        }

    fun selectSource(id: String) = viewModelScope.launch { c.sources.select(id) }

    fun refresh() {
        refresh.value++
        viewModelScope.launch { c.health.checkAll(force = true) }
    }

    fun prefetch(source: Source, vod: Vod) = c.cms.prefetch(source, vod.id)

    fun dismissNotice(n: Notice) = viewModelScope.launch { c.settings.setDismissedNotice(n.id + n.text.hashCode()) }
}

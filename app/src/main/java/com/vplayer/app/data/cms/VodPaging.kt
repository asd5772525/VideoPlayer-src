package com.vplayer.app.data.cms

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Paging 3 数据源。首屏命中过期缓存时立即返回缓存，并在后台刷新，内容变化后 invalidate 重载（SWR）。
 */
class VodPagingSource(
    private val cms: CmsRepository,
    private val source: Source,
    private val typeId: String?,
    private val keyword: String?,
    private val scope: CoroutineScope,
) : PagingSource<Int, Vod>() {

    override fun getRefreshKey(state: PagingState<Int, Vod>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Vod> {
        val pg = params.key ?: 1
        return try {
            val (page, stale) = cms.page(source, pg, typeId, keyword)
            if (stale) scope.launch {
                if (cms.refreshPage(source, pg, typeId)) invalidate()
            }
            LoadResult.Page(
                data = page.list,
                prevKey = null,
                nextKey = if (page.list.isNotEmpty() && pg < page.pageCount) pg + 1 else null,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

fun vodPager(cms: CmsRepository, source: Source, typeId: String?, keyword: String?, scope: CoroutineScope): Flow<PagingData<Vod>> =
    Pager(PagingConfig(pageSize = 20, prefetchDistance = 12, initialLoadSize = 20, enablePlaceholders = false)) {
        VodPagingSource(cms, source, typeId, keyword, scope)
    }.flow

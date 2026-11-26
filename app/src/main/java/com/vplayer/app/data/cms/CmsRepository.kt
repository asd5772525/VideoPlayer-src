package com.vplayer.app.data.cms

import android.util.LruCache
import com.vplayer.app.data.db.AppDatabase
import com.vplayer.app.data.db.ApiCacheEntity
import com.vplayer.app.data.model.Category
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import com.vplayer.app.data.model.VodPage
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内容仓库：Room 缓存 API 响应，stale-while-revalidate ——
 * 先瞬间返回缓存，再后台刷新，有变化再推送。列表接口用 ac=detail 获取（自带封面与播放地址），
 * 列表里的每条数据同时预热到详情缓存，打开详情页几乎零等待。
 */
class CmsRepository(private val db: AppDatabase, private val scope: CoroutineScope) {
    private val cacheDao = db.cacheDao()
    private val detailMem = LruCache<String, Vod>(400)
    private val pageMem = LruCache<String, VodPage>(64)

    companion object {
        const val FRESH_LIST_MS = 5 * 60_000L
        const val FRESH_DETAIL_MS = 10 * 60_000L
        const val FRESH_CLASS_MS = 24 * 3600_000L
    }

    private fun now() = System.currentTimeMillis()

    private suspend fun network(url: String): String = withContext(Dispatchers.IO) {
        val body = Http.client.getString(url)
        cacheDao.put(ApiCacheEntity(url, body, now()))
        body
    }

    /** SWR：有缓存先发缓存；缓存过期再请求网络，内容变化才再次发射 */
    private fun swr(url: String, freshMs: Long): Flow<String> = flow {
        val c = cacheDao.get(url)
        if (c != null) emit(c.body)
        if (c == null || now() - c.time > freshMs) {
            try {
                val b = network(url)
                if (b != c?.body) emit(b)
            } catch (e: Exception) {
                if (c == null) throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun seed(source: Source, page: VodPage) {
        page.list.forEach { v -> if (v.hasPlayData) detailMem.put(dKey(source.id, v.id), v) }
    }

    private fun dKey(sid: String, vid: String) = "d|$sid|$vid"

    // ---------------- 分类 ----------------
    fun categories(source: Source): Flow<List<Category>> = flow {
        swr(MacCms.listUrl(source.api), FRESH_CLASS_MS).collect { body ->
            val cats = runCatching { MacCms.parse(body).categories }.getOrDefault(emptyList())
            if (cats.isNotEmpty()) emit(cats)
        }
    }.flowOn(Dispatchers.Default)

    // ---------------- 列表（首页 / 分类 / 最近更新） ----------------
    fun pageFlow(source: Source, pg: Int = 1, typeId: String? = null, hours: Int? = null): Flow<VodPage> = flow {
        val url = MacCms.detailListUrl(source.api, pg, typeId, null, hours)
        swr(url, FRESH_LIST_MS).collect { body ->
            val p = MacCms.parse(body)
            seed(source, p)
            pageMem.put(url, p)
            emit(p)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Paging 用：返回 (页数据, 是否来自过期缓存)。
     * 来自过期缓存时调用方应在后台 [refreshPage] 并在变化时 invalidate。
     */
    suspend fun page(source: Source, pg: Int, typeId: String?, keyword: String?): Pair<VodPage, Boolean> =
        withContext(Dispatchers.IO) {
            if (!keyword.isNullOrBlank()) return@withContext search(source, keyword, pg) to false
            val url = MacCms.detailListUrl(source.api, pg, typeId)
            pageMem.get(url)?.let { return@withContext it to false }
            val c = cacheDao.get(url)
            if (c != null) {
                val p = runCatching { MacCms.parse(c.body) }.getOrNull()
                if (p != null) {
                    seed(source, p)
                    pageMem.put(url, p)
                    return@withContext p to (now() - c.time > FRESH_LIST_MS)
                }
            }
            val p = MacCms.parse(network(url))
            seed(source, p); pageMem.put(url, p)
            p to false
        }

    /** 后台刷新某页，返回内容是否变化 */
    suspend fun refreshPage(source: Source, pg: Int, typeId: String?): Boolean = withContext(Dispatchers.IO) {
        val url = MacCms.detailListUrl(source.api, pg, typeId)
        val old = cacheDao.get(url)?.body
        val body = runCatching { network(url) }.getOrNull() ?: return@withContext false
        pageMem.remove(url)
        body != old
    }

    suspend fun search(source: Source, keyword: String, pg: Int = 1): VodPage = withContext(Dispatchers.IO) {
        val url = MacCms.detailListUrl(source.api, pg, null, keyword)
        val p = runCatching { MacCms.parse(network(url)) }.getOrNull()
        if (p != null && p.list.isNotEmpty()) {
            seed(source, p); return@withContext p
        }
        // 部分源 detail 不支持 wd，退回 ac=list 搜索后批量补全详情
        val lp = MacCms.parse(network(MacCms.searchListUrl(source.api, keyword, pg)))
        if (lp.list.isEmpty()) return@withContext lp
        val full = runCatching { details(source, lp.list.map { it.id }) }.getOrDefault(emptyList())
        val byId = full.associateBy { it.id }
        lp.copy(list = lp.list.map { byId[it.id] ?: it })
    }

    // ---------------- 详情 ----------------
    fun cachedDetail(source: Source, vodId: String): Vod? = detailMem.get(dKey(source.id, vodId))

    fun detailFlow(source: Source, vodId: String): Flow<Vod> = flow {
        val key = dKey(source.id, vodId)
        var emitted: Vod? = detailMem.get(key)
        emitted?.let { emit(it) }
        swr(MacCms.detailUrl(source.api, listOf(vodId)), FRESH_DETAIL_MS).collect { body ->
            val v = runCatching { MacCms.parse(body).list.firstOrNull() }.getOrNull()
            if (v != null && v != emitted) {
                detailMem.put(key, v)
                emitted = v
                emit(v)
            }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun detail(source: Source, vodId: String, forceNetwork: Boolean = false): Vod? = withContext(Dispatchers.IO) {
        val key = dKey(source.id, vodId)
        if (!forceNetwork) detailMem.get(key)?.let { return@withContext it }
        val url = MacCms.detailUrl(source.api, listOf(vodId))
        if (!forceNetwork) cacheDao.get(url)?.let { c ->
            runCatching { MacCms.parse(c.body).list.firstOrNull() }.getOrNull()?.let { detailMem.put(key, it); return@withContext it }
        }
        MacCms.parse(network(url)).list.firstOrNull()?.also { detailMem.put(key, it) }
    }

    /** 批量详情（收藏更新检查 / 预取） */
    suspend fun details(source: Source, ids: List<String>): List<Vod> = withContext(Dispatchers.IO) {
        ids.chunked(20).flatMap { chunk ->
            val list = MacCms.parse(Http.client.getString(MacCms.detailUrl(source.api, chunk))).list
            list.forEach { detailMem.put(dKey(source.id, it.id), it) }
            list
        }
    }

    // ---------------- 预取：可见 / 长按 时批量预热详情 ----------------
    private val pending = mutableMapOf<String, MutableSet<String>>()
    private val pendingSources = mutableMapOf<String, Source>()
    private val prefetchLock = Mutex()
    private var prefetchJob: Job? = null

    fun prefetch(source: Source, vodId: String) {
        if (detailMem.get(dKey(source.id, vodId)) != null) return
        scope.launch {
            prefetchLock.withLock {
                pending.getOrPut(source.id) { mutableSetOf() }.add(vodId)
                pendingSources[source.id] = source
                if (prefetchJob?.isActive == true) return@launch
                prefetchJob = scope.launch {
                    delay(300) // 去抖，合并成一次批量请求
                    val batch = prefetchLock.withLock {
                        val b = pending.map { (sid, ids) -> pendingSources.getValue(sid) to ids.toList() }
                        pending.clear(); b
                    }
                    batch.forEach { (src, ids) -> runCatching { details(src, ids) } }
                }
            }
        }
    }

    suspend fun clearCache() {
        cacheDao.clear(); detailMem.evictAll(); pageMem.evictAll()
    }
}

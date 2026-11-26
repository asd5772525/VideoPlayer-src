package com.vplayer.app.data.cms

import com.vplayer.app.data.db.AppDatabase
import com.vplayer.app.data.db.HealthEntity
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.await
import com.vplayer.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 后台并发测速所有源与解析接口：
 * - 结果写入 health 表，列表按耗时排序（失败标红置底）
 * - 连续失败计数，成功即清零；达到 3 次自动移除（本地源删除、内置源本地隐藏），可在设置中恢复
 * - 全部失败时视为本机断网，不累计失败次数
 */
/** 纯逻辑（便于单元测试）：连续失败计数与自动移除判定 */
object HealthPolicy {
    /**
     * @param offline 本机断网（或全部失败且无法确认网络正常）时不累计失败次数
     */
    fun nextFailCount(old: Int, ok: Boolean, offline: Boolean): Int = when {
        ok -> 0
        offline -> old
        else -> old + 1
    }

    fun shouldRemove(failCount: Int) = failCount >= HealthEntity.MAX_FAIL

    /** 请求 ?ac=list，能解析出分类或列表即为成功，返回耗时 ms，失败 -1 */
    suspend fun probeSource(api: String, timeoutMs: Long = HealthChecker.TIMEOUT_MS): Long {
        val start = System.nanoTime()
        return try {
            val req = Request.Builder().url(MacCms.listUrl(api)).build()
            Http.quick(timeoutMs).newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return -1L
                val body = resp.body?.string() ?: return -1L
                val page = MacCms.parse(body)
                if (page.categories.isEmpty() && page.list.isEmpty()) -1L
                else (System.nanoTime() - start) / 1_000_000
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /** 解析接口：能连通（非 5xx）即视为可用，返回首包耗时 */
    suspend fun probeParse(url: String, timeoutMs: Long = HealthChecker.TIMEOUT_MS): Long {
        val start = System.nanoTime()
        return try {
            val req = Request.Builder().url(url).header("Range", "bytes=0-1023").build()
            Http.quick(timeoutMs).newCall(req).await().use { resp ->
                if (resp.code >= 500) -1L else (System.nanoTime() - start) / 1_000_000
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 判断本轮是否视为“本机断网”：
     * - 系统报告无可用网络 → 断网
     * - 全部失败且目标不止一个 → 多半是断网
     * - 只有一个目标且失败，但系统确认网络可用 → 不是断网，正常累计（否则唯一的坏源永远不会被移除）
     */
    fun isOffline(allFailed: Boolean, targetCount: Int, networkValidated: Boolean?): Boolean = when {
        networkValidated == false -> true
        !allFailed -> false
        targetCount > 1 -> true
        else -> networkValidated != true
    }
}

class HealthChecker(
    private val db: AppDatabase,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    /** 系统是否确认当前网络可用（null = 未知） */
    private val networkValidated: () -> Boolean? = { null },
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    companion object {
        const val MIN_INTERVAL_MS = 3 * 3600_000L
        const val TIMEOUT_MS = 6000L
    }

    private data class Target(val id: String, val kind: String, val name: String, val url: String, val type: String, val builtIn: Boolean)
    private data class Result(val t: Target, val latency: Long)

    suspend fun checkAll(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            if (!force && System.currentTimeMillis() - settings.current().lastHealthCheck < MIN_INTERVAL_MS) return@withContext
            _running.value = true
            val targets = sources.sources.value.map {
                Target(it.source.id, HealthEntity.KIND_SOURCE, it.source.name, it.source.api, it.source.type, it.source.builtIn)
            } + sources.parses.value.map {
                Target(it.parse.id, HealthEntity.KIND_PARSE, it.parse.name, it.parse.url, it.parse.type, it.parse.builtIn)
            }
            if (targets.isEmpty()) return@withContext
            val results = coroutineScope {
                targets.map { t ->
                    async { Result(t, if (t.kind == HealthEntity.KIND_SOURCE) testSource(t.url) else testParse(t.url)) }
                }.awaitAll()
            }
            val allFailed = results.none { it.latency >= 0 }
            val offline = HealthPolicy.isOffline(allFailed, results.size, runCatching { networkValidated() }.getOrNull())
            val now = System.currentTimeMillis()
            val dao = db.healthDao()
            for (r in results) {
                val old = dao.get(r.t.id)
                val fail = HealthPolicy.nextFailCount(old?.failCount ?: 0, r.latency >= 0, offline)
                val remove = HealthPolicy.shouldRemove(fail)
                dao.upsert(
                    HealthEntity(
                        id = r.t.id, kind = r.t.kind, name = r.t.name, url = r.t.url, type = r.t.type,
                        builtIn = r.t.builtIn, latencyMs = r.latency, failCount = fail, lastCheck = now, removed = remove,
                    )
                )
                if (remove && !r.t.builtIn) {
                    val rowId = r.t.id.removePrefix("l:").toLongOrNull()
                    if (rowId != null) {
                        if (r.t.kind == HealthEntity.KIND_SOURCE) db.sourceDao().deleteSource(rowId)
                        else db.sourceDao().deleteParse(rowId)
                    }
                }
            }
            settings.setLastHealthCheck(now)
        } finally {
            _running.value = false
            mutex.unlock()
        }
    }

    suspend fun testSource(api: String): Long = HealthPolicy.probeSource(api, TIMEOUT_MS)
    suspend fun testParse(url: String): Long = HealthPolicy.probeParse(url, TIMEOUT_MS)
}

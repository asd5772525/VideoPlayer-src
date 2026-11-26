package com.vplayer.app.data.cms

import com.vplayer.app.data.config.AppConfig
import com.vplayer.app.data.config.ConfigLoader.builtInParses
import com.vplayer.app.data.config.ConfigLoader.builtInSources
import com.vplayer.app.data.db.AppDatabase
import com.vplayer.app.data.db.HealthEntity
import com.vplayer.app.data.db.LocalParseEntity
import com.vplayer.app.data.db.LocalSourceEntity
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.getString
import com.vplayer.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 列表项：源 + 测速结果 */
data class SourceItem(val source: Source, val latencyMs: Long, val failCount: Int, val lastCheck: Long) {
    val tested get() = lastCheck > 0
    val ok get() = tested && latencyMs >= 0
}

data class ParseItem(val parse: ParseApi, val latencyMs: Long, val failCount: Int, val lastCheck: Long) {
    val ok get() = lastCheck > 0 && latencyMs >= 0
}

/** 按测速排序：成功的按耗时升序 → 未测 → 失败（标红置底） */
internal fun rank(lastCheck: Long, latency: Long): Long = when {
    lastCheck > 0 && latency >= 0 -> latency
    lastCheck == 0L -> 1_000_000L
    else -> 10_000_000L
}

/** 合并内置源 + 本地源与测速结果：过滤禁用 / 已自动移除，按测速排序（纯函数，便于测试） */
fun buildSourceItems(all: List<Source>, health: List<HealthEntity>): List<SourceItem> {
    val hm = health.associateBy { it.id }
    return all
        .filter { it.enabled && hm[it.id]?.removed != true }
        .map { s -> val e = hm[s.id]; SourceItem(s, e?.latencyMs ?: -1, e?.failCount ?: 0, e?.lastCheck ?: 0) }
        .sortedWith(compareBy({ rank(it.lastCheck, it.latencyMs) }, { it.source.order }))
}

class SourceRepository(
    private val config: AppConfig,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    val builtInSources: List<Source> = config.builtInSources()
    val builtInParses: List<ParseApi> = config.builtInParses()

    private val localSources: Flow<List<Source>> = db.sourceDao().observeSources().map { list ->
        list.map { Source("l:${it.rowId}", it.name, it.api, it.type, false, it.enabled, false, it.sort) }
    }
    private val localParses: Flow<List<ParseApi>> = db.sourceDao().observeParses().map { list ->
        list.map { ParseApi("l:${it.rowId}", it.name, it.url, it.type, false, it.enabled, it.sort) }
    }
    private val health = db.healthDao().observeAll()

    /** 可用的源（排除已自动移除 / 禁用），按测速排序 */
    val sources: StateFlow<List<SourceItem>> = combine(localSources, health) { local, h ->
        buildSourceItems(builtInSources + local, h)
    }.stateIn(scope, SharingStarted.Eagerly, builtInSources.filter { it.enabled }.map { SourceItem(it, -1, 0, 0) })

    val parses: StateFlow<List<ParseItem>> = combine(localParses, health) { local, h ->
        val hm = h.associateBy { it.id }
        (builtInParses + local)
            .filter { it.enabled && hm[it.id]?.removed != true }
            .map { p -> val e = hm[p.id]; ParseItem(p, e?.latencyMs ?: -1, e?.failCount ?: 0, e?.lastCheck ?: 0) }
            .sortedWith(compareBy({ rank(it.lastCheck, it.latencyMs) }, { it.parse.order }))
    }.stateIn(scope, SharingStarted.Eagerly, builtInParses.filter { it.enabled }.map { ParseItem(it, -1, 0, 0) })

    /** 已自动移除的源 / 解析（可在设置中恢复） */
    val removed: Flow<List<HealthEntity>> = health.map { l -> l.filter { it.removed } }

    /** 当前源：用户选择 → 配置的默认源 → 测速最快 */
    val current: StateFlow<Source?> = combine(sources, settings.state) { list, s ->
        list.firstOrNull { it.source.id == s.currentSourceId }?.source
            ?: list.firstOrNull { it.source.isDefault }?.source
            ?: list.firstOrNull()?.source
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** 按 id 查找（包括已移除的，便于历史 / 收藏继续使用） */
    suspend fun byId(id: String): Source? {
        sources.value.firstOrNull { it.source.id == id }?.let { return it.source }
        builtInSources.firstOrNull { it.id == id }?.let { return it }
        if (id.startsWith("l:")) {
            val h = db.healthDao().get(id)
            if (h != null) return Source(id, h.name, h.url, h.type, false)
        }
        return null
    }

    suspend fun select(id: String) = settings.setCurrentSource(id)

    // ---- 本地源管理 ----
    suspend fun addLocalSource(name: String, api: String, type: String = detectType(api)): Boolean {
        if (api.isBlank() || db.sourceDao().countSourceByApi(api.trim()) > 0) return false
        if (builtInSources.any { it.api == api.trim() }) return false
        db.sourceDao().insertSource(LocalSourceEntity(name = name.ifBlank { hostOf(api) }, api = api.trim(), type = type))
        return true
    }

    suspend fun updateLocalSource(id: String, name: String, api: String, type: String, enabled: Boolean = true) {
        val rowId = id.removePrefix("l:").toLongOrNull() ?: return
        db.sourceDao().updateSource(LocalSourceEntity(rowId, name, api.trim(), type, enabled))
    }

    suspend fun deleteLocalSource(id: String) {
        id.removePrefix("l:").toLongOrNull()?.let { db.sourceDao().deleteSource(it) }
        db.healthDao().delete(id)
    }

    suspend fun addLocalParse(name: String, url: String, type: String = "json"): Boolean {
        if (url.isBlank() || db.sourceDao().countParseByUrl(url.trim()) > 0) return false
        db.sourceDao().insertParse(LocalParseEntity(name = name.ifBlank { hostOf(url) }, url = url.trim(), type = type))
        return true
    }

    suspend fun updateLocalParse(id: String, name: String, url: String, type: String) {
        val rowId = id.removePrefix("l:").toLongOrNull() ?: return
        db.sourceDao().updateParse(LocalParseEntity(rowId, name, url.trim(), type))
    }

    suspend fun deleteLocalParse(id: String) {
        id.removePrefix("l:").toLongOrNull()?.let { db.sourceDao().deleteParse(it) }
        db.healthDao().delete(id)
    }

    /** 恢复被自动移除的条目：内置的取消隐藏；本地的重新插入 */
    suspend fun restore(h: HealthEntity) {
        db.healthDao().delete(h.id)
        if (!h.builtIn) {
            if (h.kind == HealthEntity.KIND_SOURCE) addLocalSource(h.name, h.url, h.type)
            else addLocalParse(h.name, h.url, h.type)
        }
    }

    /**
     * 订阅导入：支持
     *  [{"name","api","type"}] 或 {"sources":[...],"parses":[...]} 或 TVBox 风格 {"sites":[{"name","api","type":1}],"parses":[{"name","url"}]}
     */
    suspend fun importSubscription(url: String): Pair<Int, Int> {
        val body = Http.client.getString(url)
        val root = MacCms.json.parseToJsonElement(body)
        var s = 0
        var p = 0
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
        val srcArr: JsonArray? = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["sources"] ?: root["sites"]) as? JsonArray
            else -> null
        }
        srcArr?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val api = o.str("api").ifBlank { o.str("url") }
            if (!api.startsWith("http")) return@forEach
            val t = when (o.str("type")) { "0", "xml" -> "xml"; else -> detectType(api) }
            if (addLocalSource(o.str("name"), api, t)) s++
        }
        ((root as? JsonObject)?.get("parses") as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val u = o.str("url")
            if (!u.startsWith("http")) return@forEach
            val t = if (o.str("type") == "webview" || o.str("type") == "0") "webview" else "json"
            if (addLocalParse(o.str("name"), u, t)) p++
        }
        return s to p
    }

    companion object {
        fun detectType(api: String) = if (api.contains("/at/xml") || api.endsWith(".xml")) "xml" else "json"
        fun hostOf(url: String) = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url.take(24)
    }
}

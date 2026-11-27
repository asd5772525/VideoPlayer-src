package com.vplayer.app.player

import com.vplayer.app.data.config.AdFilterCfg
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.Source
import com.vplayer.app.player.ad.AdRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 解析接口排序（v1.2）：线路绑定 → 源绑定 → 本源专属解析（其余）→ 通用解析（已按测速排序），去重。
 * - 专属解析（ownerId 非空）只给它所属的源用，别的源看不到
 * - 专属解析只写在 lineParses 里（限定了线路）的，只在那些线路上用
 * - 绑定了但已删除 / 停用的 key 忽略
 */
object ParseOrder {
    fun forSource(all: List<ParseApi>, source: Source?, lineName: String?): List<ParseApi> {
        val usable = all.filter { it.ownerId.isEmpty() || (source != null && it.ownerId == source.id) }
        if (source == null) return usable
        val byKey = usable.filter { it.key.isNotBlank() }.associateBy { it.key }
        val lineKeys = lineName?.let { n ->
            source.lineParses[n] ?: source.lineParses.entries.firstOrNull { it.key.trim().equals(n.trim(), true) }?.value
        }.orEmpty()
        val bound = (lineKeys + source.parseKeys).mapNotNull { byKey[it] }
        val lineOnly = source.lineParses.values.flatten().toSet() - source.parseKeys.toSet()
        val owned = usable.filter { it.ownerId.isNotEmpty() && it.key !in lineOnly }
        val global = usable.filter { it.ownerId.isEmpty() }
        return (bound + owned + global).distinctBy { it.id }
    }
}

/**
 * 解析竞速：按顺序同时跑 [width] 个候选，谁先返回非空结果用谁，其余取消；
 * 某个失败了就补上下一个（滑动窗口），全部失败返回 null。width = 1 就是原来的逐个尝试。
 */
object ParseRace {
    suspend fun <T, R : Any> first(candidates: List<T>, width: Int, block: suspend (T) -> R?): Pair<T, R>? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val w = width.coerceAtLeast(1)
        val running = mutableListOf<Deferred<Pair<T, R?>>>()
        var next = 0
        fun launchNext() {
            val c = candidates[next++]
            running += async {
                val r = try { block(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                c to r
            }
        }
        while (next < candidates.size && running.size < w) launchNext()
        while (running.isNotEmpty()) {
            val (d, res) = select { running.forEach { d -> d.onAwait { d to it } } }
            running.remove(d)
            val r = res.second
            if (r != null) {
                running.forEach { it.cancel() }
                return@coroutineScope res.first to r
            }
            if (next < candidates.size) launchNext()
        }
        null
    }
}

/** 从解析接口的 JSON 返回里取视频地址 */
object JsonPick {
    val DEFAULT_PATHS = listOf("url", "data.url", "playurl", "data.playurl", "m3u8", "data", "result.url")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun pick(body: String, path: String = ""): String? {
        val t = body.trim().trimStart('\uFEFF')
        if (t.startsWith("http")) return t.lineSequence().first().trim().takeIf { it.startsWith("http") }
        if (!t.startsWith("{") && !t.startsWith("[")) return null
        val root = runCatching { json.parseToJsonElement(t) }.getOrNull() ?: return null
        val paths = path.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_PATHS }
        for (p in paths) {
            val v = at(root, p) ?: continue
            if (v.startsWith("http") || v.startsWith("//")) return if (v.startsWith("//")) "https:$v" else v
        }
        return null
    }

    /** 点路径：a.b.0.c（数字 = 数组下标） */
    fun at(root: JsonElement, path: String): String? {
        var cur: JsonElement = root
        for (part in path.split('.')) {
            cur = when (cur) {
                is JsonObject -> cur[part] ?: return null
                is JsonArray -> part.toIntOrNull()?.let { cur.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return (cur as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
    }
}

/** 合并 App 全局开关与源的 adFilter 配置，得到实际生效的规则；null = 不过滤 */
object AdRules {
    fun effective(cfg: AdFilterCfg?, globalOn: Boolean): AdRule? {
        if (!globalOn) return null
        if (cfg == null) return AdRule()
        if (!cfg.enabled) return null
        return AdRule(
            smart = true,
            keywords = cfg.keywords.map { it.trim() }.filter { it.isNotEmpty() },
            regex = cfg.regex,
            maxAdSeconds = cfg.discontinuityMax.coerceIn(5, 300).toDouble(),
        )
    }
}

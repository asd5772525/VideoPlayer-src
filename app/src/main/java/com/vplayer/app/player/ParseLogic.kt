package com.vplayer.app.player

import com.vplayer.app.data.config.AdFilterCfg
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.Source
import com.vplayer.app.player.ad.AdRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 解析接口排序：线路绑定 → 源绑定 → 其余（已按测速排序），去重；绑定了但已删除 / 停用的 key 忽略 */
object ParseOrder {
    fun forSource(all: List<ParseApi>, source: Source?, lineName: String?): List<ParseApi> {
        if (source == null) return all
        val byKey = all.filter { it.key.isNotBlank() }.associateBy { it.key }
        val lineKeys = lineName?.let { n ->
            source.lineParses[n] ?: source.lineParses.entries.firstOrNull { it.key.trim().equals(n.trim(), true) }?.value
        }.orEmpty()
        val bound = (lineKeys + source.parseKeys).mapNotNull { byKey[it] }
        return (bound + all).distinctBy { it.id }
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

package com.vplayer.app.data.config

import android.content.Context
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 构建时打包进 assets 的 config.json（由仓库根目录 config.json 复制而来）。
 * 运行时不联网拉取：内置源 / 解析 / 播放器默认值 / 公告 随版本固定，经 OTA 更新。
 */
@Serializable
data class AppConfig(
    val configVersion: Int = 0,
    val sources: List<SourceCfg> = emptyList(),
    val parses: List<ParseCfg> = emptyList(),
    val player: PlayerDefaults = PlayerDefaults(),
    val notice: Notice = Notice(),
    val updateUrl: String = "",
)

@Serializable
data class SourceCfg(
    val key: String = "",
    val name: String = "",
    val api: String = "",
    val type: String = "json",
    val enabled: Boolean = true,
    val order: Int = 0,
    val default: Boolean = false,
    /** 优先使用的解析接口（parses[].key），按顺序；没绑定的解析排在后面 */
    val parses: List<String> = emptyList(),
    /** 按线路名（vod_play_from）绑定解析，优先级高于 [parses] */
    val lineParses: Map<String, List<String>> = emptyMap(),
    /** 插播广告过滤规则；不写 = 只用智能识别（受 App 全局开关控制），enabled=false = 本源不过滤 */
    val adFilter: AdFilterCfg? = null,
    /** v1.2：首选播放内核（exo / ijk / system），空 = 跟随 App 设置。只在 App 里选「自动」时生效 */
    val kernel: String = "",
)

@Serializable
data class AdFilterCfg(
    val enabled: Boolean = true,
    val keywords: List<String> = emptyList(),
    val regex: String = "",
    val discontinuityMax: Int = 30,
)

@Serializable
data class ParseCfg(
    val key: String = "",
    val name: String = "",
    val url: String = "",
    val type: String = "json",
    val enabled: Boolean = true,
    val order: Int = 0,
    /** 请求解析接口（以及播放解析出的地址）时附带的请求头，常用 Referer / User-Agent */
    val headers: Map<String, String> = emptyMap(),
    /** 超时秒数，0 = 默认（JSON 10 秒，网页嗅探 15 秒） */
    val timeout: Int = 0,
    /** JSON 返回中视频地址的字段路径，如 data.url；留空按常见字段依次尝试 */
    val jsonPath: String = "",
    /**
     * v1.2：所属播放源的 key。非空 = 这个解析是该源专用的（只给这个源用）；
     * 空 = 通用解析（所有源共用，兼容 v1.1）。
     */
    val owner: String = "",
)

@Serializable
data class PlayerDefaults(
    val bufferSeconds: Int = 30,
    val hwDecode: Boolean = true,
    val preloadNext: Boolean = true,
    val autoNext: Boolean = true,
    val skipIntro: Int = 0,
    val skipOutro: Int = 0,
    val defaultSpeed: Float = 1.0f,
    val longPressSpeed: Float = 3.0f,
    val directFirst: Boolean = true,
    val autoSpeedTest: Boolean = true,
    val downloadConcurrency: Int = 8,
    val maxDownloadTasks: Int = 2,
    /** v1.2：默认播放内核 auto / exo / ijk / system */
    val kernel: String = "auto",
    /** v1.2：加载策略 fast / balanced / smooth / custom（custom 时用下面这些值 + bufferSeconds / preloadNext） */
    val preset: String = "balanced",
    val minBufferSeconds: Int = 15,
    val startMs: Int = 1000,
    val rebufferMs: Int = 2000,
    val cacheMb: Int = 512,
    val connectTimeout: Int = 8,
    val readTimeout: Int = 15,
    val dnsPrefetch: Boolean = true,
    /** 前两个解析接口同时请求，谁先返回有效地址用谁 */
    val parseRace: Boolean = true,
)

@Serializable
data class Notice(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "公告",
    val text: String = "",
    val link: String = "",
)

object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun load(context: Context): AppConfig = try {
        context.assets.open("config.json").use { parse(it.readBytes().decodeToString()) }
    } catch (e: Exception) {
        AppConfig()
    }

    /** 解析 config.json 文本：先把「写在播放源里的解析对象」整理成标准格式（见 [ConfigNormalizer]） */
    fun parse(text: String): AppConfig =
        json.decodeFromJsonElement(AppConfig.serializer(), ConfigNormalizer.normalize(json.parseToJsonElement(text)))

    fun AppConfig.builtInSources(): List<Source> = sources
        .filter { it.api.isNotBlank() }
        .mapIndexed { i, s ->
            Source(
                id = "b:" + s.key.ifBlank { s.api.hashCode().toString() },
                name = s.name.ifBlank { "内置源${i + 1}" },
                api = s.api.trim(), type = s.type, builtIn = true,
                enabled = s.enabled, isDefault = s.default, order = s.order,
                parseKeys = s.parses, lineParses = s.lineParses, adFilter = s.adFilter,
                kernel = s.kernel.trim().lowercase(),
            )
        }.sortedBy { it.order }

    fun AppConfig.builtInParses(): List<ParseApi> = parses
        .filter { it.url.isNotBlank() }
        .mapIndexed { i, p ->
            ParseApi(
                id = "b:" + p.key.ifBlank { p.url.hashCode().toString() },
                name = p.name.ifBlank { "内置解析${i + 1}" },
                url = p.url.trim(), type = p.type, builtIn = true,
                enabled = p.enabled, order = p.order,
                key = p.key, headers = p.headers, timeoutSec = p.timeout, jsonPath = p.jsonPath,
                ownerId = p.owner.takeIf { it.isNotBlank() }?.let { "b:$it" } ?: "",
            )
        }.sortedBy { it.order }
}

/**
 * config.json 兼容整理（纯函数，便于测试）：
 * - v1.1：sources[].parses 是解析 key 列表、lineParses 是 线路名 → key 列表，原样保留
 * - v1.2：sources[].parses / lineParses 里也可以直接写解析对象 {"url": "...", "name": "...", ...}，
 *   这里把它挪到顶层 parses（owner = 源的 key，自动生成 key），源里只留 key
 * 后台页面保存时已经是标准格式，这里主要照顾手改 config.json 的情况。
 */
object ConfigNormalizer {
    fun normalize(root: JsonElement): JsonElement {
        val obj = root as? JsonObject ?: return root
        val sources = obj["sources"] as? JsonArray ?: return root
        val parses = (obj["parses"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val usedKeys = parses.mapNotNull { ((it as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull }.toMutableSet()
        var changed = false
        val newSources = sources.mapIndexed { si, el ->
            val s = el as? JsonObject ?: return@mapIndexed el
            val sKey = (s["key"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "s${si + 1}"
            var n = 0
            var hoisted = false
            fun hoist(arr: JsonElement?): JsonArray? {
                val a = arr as? JsonArray ?: return null
                if (a.none { it is JsonObject }) return a
                changed = true
                hoisted = true
                return JsonArray(a.mapNotNull { item ->
                    when (item) {
                        is JsonObject -> {
                            val url = (item["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            if (url.isEmpty()) return@mapNotNull null
                            val given = (item["key"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            // 同一个 key 已存在且地址相同：直接引用；否则（没写 key 或 key 冲突）生成新 key
                            val same = given != null && parses.any { p ->
                                val o = p as? JsonObject
                                (o?.get("key") as? JsonPrimitive)?.contentOrNull == given && (o["url"] as? JsonPrimitive)?.contentOrNull?.trim() == url
                            }
                            var key = given
                            if (!same && (key == null || key in usedKeys)) {
                                do { key = "${sKey}_p${++n}" } while (key in usedKeys)
                            }
                            if (!same) {
                                usedKeys += key!!
                                parses += JsonObject(item + mapOf("key" to JsonPrimitive(key), "url" to JsonPrimitive(url),
                                    "owner" to (item["owner"] ?: JsonPrimitive(sKey))))
                            }
                            JsonPrimitive(key)
                        }
                        is JsonPrimitive -> item
                        else -> null
                    }
                })
            }
            val ps = hoist(s["parses"])
            val lp = (s["lineParses"] as? JsonObject)?.let { m -> JsonObject(m.mapValues { (_, v) -> hoist(v) ?: v }) }
            var out: Map<String, JsonElement> = s
            if (ps != null) out = out + ("parses" to ps)
            if (lp != null) out = out + ("lineParses" to lp)
            if ((s["key"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() && hoisted) out = out + ("key" to JsonPrimitive(sKey))
            JsonObject(out)
        }
        if (!changed) return root
        return JsonObject(obj + mapOf("sources" to JsonArray(newSources), "parses" to JsonArray(parses)))
    }
}

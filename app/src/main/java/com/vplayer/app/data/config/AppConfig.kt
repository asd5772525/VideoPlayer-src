package com.vplayer.app.data.config

import android.content.Context
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        context.assets.open("config.json").use { json.decodeFromString(AppConfig.serializer(), it.readBytes().decodeToString()) }
    } catch (e: Exception) {
        AppConfig()
    }

    fun AppConfig.builtInSources(): List<Source> = sources
        .filter { it.api.isNotBlank() }
        .mapIndexed { i, s ->
            Source(
                id = "b:" + s.key.ifBlank { s.api.hashCode().toString() },
                name = s.name.ifBlank { "内置源${i + 1}" },
                api = s.api.trim(), type = s.type, builtIn = true,
                enabled = s.enabled, isDefault = s.default, order = s.order,
                parseKeys = s.parses, lineParses = s.lineParses, adFilter = s.adFilter,
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
            )
        }.sortedBy { it.order }
}

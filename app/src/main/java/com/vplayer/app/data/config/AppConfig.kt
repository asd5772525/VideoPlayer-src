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
)

@Serializable
data class ParseCfg(
    val key: String = "",
    val name: String = "",
    val url: String = "",
    val type: String = "json",
    val enabled: Boolean = true,
    val order: Int = 0,
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
            )
        }.sortedBy { it.order }
}

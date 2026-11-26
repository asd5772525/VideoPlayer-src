package com.vplayer.app.data.model

import kotlinx.serialization.Serializable

/** 统一的数据源描述（内置源来自打包进 APK 的 config.json，本地源来自 Room） */
data class Source(
    val id: String,          // 内置: "b:<key>"  本地: "l:<rowId>"
    val name: String,
    val api: String,
    val type: String,        // json / xml
    val builtIn: Boolean,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val order: Int = 0,
) {
    val isXml: Boolean get() = type.equals("xml", true) || api.contains("/at/xml")
}

data class ParseApi(
    val id: String,
    val name: String,
    val url: String,
    val type: String,        // json / webview
    val builtIn: Boolean,
    val enabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
data class Category(val id: String, val name: String)

@Serializable
data class Vod(
    val id: String,
    val name: String,
    val pic: String = "",
    val typeId: String = "",
    val typeName: String = "",
    val remarks: String = "",
    val year: String = "",
    val area: String = "",
    val lang: String = "",
    val actor: String = "",
    val director: String = "",
    val content: String = "",
    val score: String = "",
    val playFrom: String = "",
    val playUrl: String = "",
    val time: String = "",
) {
    val lines: List<PlayLine> by lazy { PlayUrlParser.parse(playFrom, playUrl) }
    val hasPlayData: Boolean get() = playUrl.isNotBlank()
}

data class PlayLine(val name: String, val episodes: List<Episode>)
data class Episode(val name: String, val url: String)

data class VodPage(
    val page: Int,
    val pageCount: Int,
    val list: List<Vod>,
    val categories: List<Category>,
)

/** 苹果CMS 播放地址解析：$$$ 分线路，# 分集，$ 分名称与地址 */
object PlayUrlParser {
    fun parse(from: String, urls: String): List<PlayLine> {
        if (urls.isBlank()) return emptyList()
        val froms = from.split("$$$")
        return urls.split("$$$").mapIndexedNotNull { i, lineStr ->
            val parts = lineStr.split("#").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            // 只有一集且没写名称（包括末尾多余的 #）→ “正片”
            val single = parts.size == 1
            val eps = parts
                .mapIndexed { j, ep ->
                    val idx = ep.indexOf('$')
                    if (idx >= 0) {
                        val name = ep.substring(0, idx).trim()
                        Episode(name.ifEmpty { if (single) "正片" else "第${j + 1}集" }, ep.substring(idx + 1).trim())
                    } else Episode(if (single) "正片" else "第${j + 1}集", ep)
                }.filter { it.url.isNotBlank() }
            if (eps.isEmpty()) null
            else PlayLine(froms.getOrNull(i)?.trim()?.ifEmpty { null } ?: "线路${i + 1}", eps)
        }
    }
}

@Serializable
data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val size: String = "",
    val changelog: List<String> = emptyList(),
    val force: Boolean = false,
    val md5: String = "",
)

fun vodKey(sourceId: String, vodId: String) = "$sourceId|$vodId"

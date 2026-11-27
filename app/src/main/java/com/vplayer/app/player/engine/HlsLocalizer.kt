package com.vplayer.app.player.engine

import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.getString
import com.vplayer.app.player.ad.AdFilter
import com.vplayer.app.player.ad.AdFilterResult
import com.vplayer.app.player.ad.AdRule
import java.io.File
import java.net.URI

/**
 * 给 IJK 内核用的「本地改写播放列表」去广告：
 * 下载 m3u8（master 取第一条码流）→ 地址全部改成绝对地址 → 用和 ExoPlayer 同一套 [AdFilter] 去掉插播分片 →
 * 写到缓存目录，IJK 播放这个本地文件（分片仍然从网上拉）。
 * 只处理点播（有 ENDLIST）；没有去掉任何分片时返回 null，直接播原地址，不多绕一步。
 * 需要自定义请求头（Referer 等）的地址不改写：本地文件里的分片请求带不上这些头。
 */
object HlsLocalizer {
    data class Local(val file: File, val result: AdFilterResult, val mediaUrl: String)

    private val uriAttr = Regex("""URI="([^"]+)"""")

    fun resolve(base: String, ref: String): String = try {
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URI(base).resolve(ref.trim()).toString()
    } catch (e: Exception) { ref }

    /** 把播放列表里的相对地址（分片、#EXT-X-KEY / #EXT-X-MAP 的 URI）全部改成绝对地址 */
    fun absolutize(text: String, base: String): String = text.lineSequence().joinToString("\n") { line ->
        val t = line.trim()
        when {
            t.isEmpty() -> line
            !t.startsWith("#") -> resolve(base, t)
            t.contains("URI=\"") -> uriAttr.replace(line) { m -> "URI=\"" + resolve(base, m.groupValues[1]) + "\"" }
            else -> line
        }
    }

    /** master playlist 里的第一条码流地址（绝对地址）；不是 master 返回 null */
    fun firstVariant(text: String, base: String): String? {
        if (!text.contains("#EXT-X-STREAM-INF")) return null
        val lines = text.lines().map { it.trim() }
        val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        return lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(base, it) }
    }

    /** 纯文本部分（可单测）：返回改写后的文本与结果；不需要改写返回 null */
    fun rewrite(mediaText: String, mediaUrl: String, rule: AdRule): AdFilterResult? {
        if (!mediaText.contains("#EXT-X-ENDLIST")) return null
        val r = AdFilter.filter(absolutize(mediaText, mediaUrl), mediaUrl, rule)
        return r.takeIf { it.changed }
    }

    suspend fun localize(url: String, rule: AdRule, dir: File): Local? {
        var mediaUrl = url
        var text = Http.client.getString(url)
        firstVariant(text, url)?.let { mediaUrl = it; text = Http.client.getString(it) }
        val r = rewrite(text, mediaUrl, rule) ?: return null
        dir.mkdirs()
        // 只留最近几个，避免越积越多
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        val f = File(dir, "ijk_${Integer.toHexString(url.hashCode())}.m3u8")
        f.writeText(r.text)
        return Local(f, r, mediaUrl)
    }
}

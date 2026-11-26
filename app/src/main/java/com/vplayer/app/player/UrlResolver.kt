package com.vplayer.app.player

import android.content.Context
import com.vplayer.app.data.cms.SourceRepository
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.getString
import com.vplayer.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放地址解析：直链优先（m3u8/mp4/flv 直接播放），否则依次尝试解析接口
 * （JSON 返回 url 字段；或 WebView 嗅探），结果内存缓存 10 分钟。
 */
class UrlResolver(
    private val context: Context,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
) {
    private data class Cached(val url: String, val time: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private val directRegex = Regex("""\.(m3u8|mp4|flv|mkv|avi|mov|ts|m4v|webm)(\?|$|#)""", RegexOption.IGNORE_CASE)

        fun isDirect(url: String): Boolean {
            val l = url.lowercase()
            return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".flv") || directRegex.containsMatchIn(l) ||
                l.startsWith("file://") || l.startsWith("/")
        }
    }

    fun cached(url: String): String? = cache[url]?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }?.url

    suspend fun resolve(raw: String): String = withContext(Dispatchers.IO) {
        val url = raw.trim()
        cached(url)?.let { return@withContext it }
        val s = settings.current()
        if (s.directFirst && isDirect(url)) return@withContext url
        val parses = sources.parses.value.map { it.parse } // 已按测速排序
        for (p in parses) {
            val target = p.url + url
            val got = if (p.type == "webview") {
                WebSniffer.sniff(context, target)
            } else {
                tryJson(target) ?: WebSniffer.sniff(context, target, timeoutMs = 12_000)
            }
            if (!got.isNullOrBlank()) {
                cache[url] = Cached(got, System.currentTimeMillis())
                return@withContext got
            }
        }
        if (isDirect(url)) return@withContext url
        // 没有解析接口：直接嗅探原始页面
        if (url.startsWith("http")) {
            WebSniffer.sniff(context, url)?.let { cache[url] = Cached(it, System.currentTimeMillis()); return@withContext it }
        }
        url
    }

    private suspend fun tryJson(target: String): String? = try {
        val body = Http.client.getString(target).trim()
        if (!body.startsWith("{")) null
        else {
            val o = json.parseToJsonElement(body) as? JsonObject
            val u = (o?.get("url") as? JsonPrimitive)?.contentOrNull
                ?: ((o?.get("data") as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            u?.takeIf { it.startsWith("http") }
        }
    } catch (e: Exception) {
        null
    }
}

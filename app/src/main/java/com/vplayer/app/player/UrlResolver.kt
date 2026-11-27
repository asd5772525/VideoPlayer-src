package com.vplayer.app.player

import android.content.Context
import com.vplayer.app.data.cms.SourceRepository
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.await
import com.vplayer.app.data.prefs.SettingsRepository
import com.vplayer.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 解析结果：真实地址 + 用到的解析接口（直链为 null）+ 播放时需要带的请求头 */
data class Resolved(val url: String, val parse: ParseApi? = null) {
    val headers: Map<String, String> get() = parse?.headers.orEmpty()
}

/**
 * 播放地址解析：直链优先（m3u8/mp4/flv 直接播放），否则依次尝试解析接口
 * （源 / 线路绑定的解析优先，其次全局解析，按测速排序）。
 * JSON 解析支持自定义请求头、超时与字段路径；失败再用 WebView 嗅探。结果内存缓存 10 分钟。
 */
class UrlResolver(
    private val context: Context,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
) {
    private data class Cached(val r: Resolved, val time: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    companion object {
        private val directRegex = Regex("""\.(m3u8|mp4|flv|mkv|avi|mov|ts|m4v|webm)(\?|$|#)""", RegexOption.IGNORE_CASE)

        fun isDirect(url: String): Boolean {
            val l = url.lowercase()
            return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".flv") || directRegex.containsMatchIn(l) ||
                l.startsWith("file://") || l.startsWith("/")
        }
    }

    fun cached(url: String): String? = cachedResolved(url)?.url

    fun cachedResolved(url: String): Resolved? =
        cache[url.trim()]?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }?.r

    /** 换线 / 换解析前调用：丢掉这条地址的缓存 */
    fun invalidate(url: String) { cache.remove(url.trim()) }

    suspend fun resolve(raw: String): String = resolveFull(raw, null, null).url

    /**
     * @param exclude 已经试过（失败）的解析 id，自动换解析时跳过
     * @param allowDirect false 时即使是直链也走解析（直链播放失败后的兜底）
     */
    suspend fun resolveFull(
        raw: String,
        source: Source?,
        lineName: String?,
        exclude: Set<String> = emptySet(),
        allowDirect: Boolean = true,
    ): Resolved = withContext(Dispatchers.IO) {
        val url = raw.trim()
        if (exclude.isEmpty()) cachedResolved(url)?.let { return@withContext it }
        val s = settings.current()
        if (allowDirect && s.directFirst && isDirect(url)) return@withContext Resolved(url)
        val ordered = ParseOrder.forSource(sources.parses.value.map { it.parse }, source, lineName)
        for (p in ordered) {
            if (p.id in exclude) continue
            val got = try {
                viaParse(p, url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("parse", "${p.name} 失败：${e.message}")
                null
            }
            if (!got.isNullOrBlank()) {
                val r = Resolved(got, p)
                cache[url] = Cached(r, System.currentTimeMillis())
                return@withContext r
            }
        }
        if (exclude.isNotEmpty()) throw IOException("没有更多可用的解析接口")
        if (allowDirect && isDirect(url)) return@withContext Resolved(url)
        // 没有解析接口：直接嗅探原始页面
        if (url.startsWith("http")) {
            WebSniffer.sniff(context, url)?.let { val r = Resolved(it); cache[url] = Cached(r, System.currentTimeMillis()); return@withContext r }
        }
        Resolved(url)
    }

    private suspend fun viaParse(p: ParseApi, url: String): String? {
        val target = p.url + url
        val referer = p.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val ua = p.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        return if (p.type == "webview") {
            WebSniffer.sniff(context, target, timeoutMs = (if (p.timeoutSec > 0) p.timeoutSec else 15) * 1000L, referer = referer, userAgent = ua)
        } else {
            tryJson(p, target) ?: WebSniffer.sniff(context, target, timeoutMs = (if (p.timeoutSec > 0) p.timeoutSec else 12) * 1000L, referer = referer, userAgent = ua)
        }
    }

    private suspend fun tryJson(p: ParseApi, target: String): String? {
        val timeout = (if (p.timeoutSec > 0) p.timeoutSec else 10).toLong()
        val client = Http.client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(target).apply { p.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }.build()
        val body = client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        return JsonPick.pick(body, p.jsonPath)
    }
}

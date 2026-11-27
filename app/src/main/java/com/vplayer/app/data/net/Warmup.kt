package com.vplayer.app.data.net

import com.vplayer.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 连接预热：提前做 DNS 解析，并向源 / 解析接口的域名发一个 HEAD 请求，把 TCP + TLS 连接放进 OkHttp 连接池。
 * 真正请求时直接复用，省掉一次握手（https 通常 100~300ms）。同一个域名 3 分钟内只预热一次。
 */
object Warmup {
    private val recent = ConcurrentHashMap<String, Long>()

    /** 取出需要预热的「协议 + 域名 + 端口」，去重（纯函数，便于测试） */
    fun origins(urls: List<String>): List<String> = urls.mapNotNull { u ->
        u.trim().toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}/" }
    }.distinct()

    suspend fun warm(urls: List<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todo = origins(urls).filter { o -> (recent[o]?.let { now - it > 180_000 } ?: true).also { if (it) recent[o] = now } }
        if (todo.isEmpty()) return@withContext
        val client = Http.quick(3_000)
        coroutineScope {
            todo.map { o ->
                async {
                    val t0 = System.currentTimeMillis()
                    runCatching {
                        o.toHttpUrlOrNull()?.host?.let { Http.client.dns.lookup(it) }
                        client.newCall(Request.Builder().url(o).head().build()).await().close()
                    }
                    AppLog.i("warmup", "预热 ${o.removeSuffix("/")} ${System.currentTimeMillis() - t0}ms")
                }
            }.awaitAll()
        }
    }
}

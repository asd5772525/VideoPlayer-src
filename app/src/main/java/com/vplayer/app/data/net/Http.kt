package com.vplayer.app.data.net

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

/** 带过期时间的 DNS 缓存，避免每次请求重复解析 */
class CachingDns(private val ttlMs: Long = 10 * 60_000L) : Dns {
    private data class Entry(val addrs: List<InetAddress>, val time: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (now - it.time < ttlMs) return it.addrs }
        val result = Dns.SYSTEM.lookup(hostname)
        cache[hostname] = Entry(result, now)
        return result
    }
}

object Http {
    @Volatile
    private var _client: OkHttpClient? = null
    val client: OkHttpClient get() = _client ?: error("Http not initialised")

    fun init(context: Context) {
        if (_client != null) return
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        _client = OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024))
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(dispatcher)
            .dns(CachingDns())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") == null)
                    chain.proceed(req.newBuilder().header("User-Agent", DEFAULT_UA).build())
                else chain.proceed(req)
            }
            .build()
    }

    /** 仅供 JVM 单元测试：注入不依赖 Context 的客户端 */
    fun installForTest(c: OkHttpClient) { _client = c }

    /** 测速等需要短超时的场景 */
    fun quick(timeoutMs: Long): OkHttpClient = client.newBuilder()
        .cache(null)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
}

/** 可取消的挂起式请求 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

suspend fun OkHttpClient.getString(url: String, headers: Map<String, String> = emptyMap()): String {
    val b = Request.Builder().url(url)
    headers.forEach { (k, v) -> b.header(k, v) }
    newCall(b.build()).await().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        return resp.body?.string() ?: ""
    }
}

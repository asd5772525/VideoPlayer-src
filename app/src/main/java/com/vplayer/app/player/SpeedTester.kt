package com.vplayer.app.player

import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 并发测速：GET 前 1KB，记录首包耗时；失败 / 超时返回 -1 */
object SpeedTester {
    suspend fun ttfb(url: String, timeoutMs: Long = 5000): Long = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext -1L
        val start = System.nanoTime()
        try {
            val req = Request.Builder().url(url).header("Range", "bytes=0-1023").build()
            Http.quick(timeoutMs).newCall(req).await().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) -1L
                else {
                    resp.body?.source()?.request(1)
                    (System.nanoTime() - start) / 1_000_000
                }
            }
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun testAll(urls: List<String>, timeoutMs: Long = 5000): List<Long> = coroutineScope {
        urls.map { async { ttfb(it, timeoutMs) } }.awaitAll()
    }
}

package com.vplayer.app.download

import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.await
import com.vplayer.app.data.net.getString
import com.vplayer.app.player.ad.AdFilter
import com.vplayer.app.player.ad.AdFilterResult
import com.vplayer.app.player.ad.AdRule
import com.vplayer.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程 M3U8 下载：
 * 1. 解析 master playlist，选择最高码率；解析 media playlist
 * 2. 并发下载 TS / fMP4 分片（失败重试），已完成的分片跳过（断点续传）
 * 3. 下载 AES-128 key 与 EXT-X-MAP，改写为本地相对路径，生成 index.m3u8 供离线播放
 * 4. [adRule] 非空时先用 [AdFilter] 去掉插播广告分片（与在线播放同一套规则），广告分片不会被下载
 */
class M3u8Downloader(
    private val dir: File,
    private val threads: Int,
    private val headers: Map<String, String> = emptyMap(),
    private val adRule: AdRule? = null,
    private val onProgress: (segDone: Int, segTotal: Int, bytes: Long) -> Unit,
) {
    /** 本次去广告结果（未过滤为 null） */
    var adResult: AdFilterResult? = null
        private set

    private data class Segment(val index: Int, val url: String, val ext: String = "ts")

    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URI(base).resolve(ref.trim()).toString()

    private val uriAttr = Regex("""URI="([^"]+)"""")

    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        var playlistUrl = url
        var text = Http.client.getString(playlistUrl, headers)
        if (!text.contains("#EXTM3U")) throw IOException("不是有效的 M3U8")

        // master playlist：选最高带宽
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var best: String? = null
            var bestBw = -1L
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                    if (next != null && bw >= bestBw) { bestBw = bw; best = next }
                }
            }
            playlistUrl = resolve(playlistUrl, best ?: throw IOException("未找到子播放列表"))
            text = Http.client.getString(playlistUrl, headers)
        }
        if (adRule != null) {
            val r = AdFilter.filter(text, playlistUrl, adRule)
            if (r.changed) {
                adResult = r
                text = r.text
                AppLog.i("ad", "下载去除 ${r.removedSegments} 个插播分片（${r.removedSeconds}s）")
            }
        }

        // 解析分片 / key / map，同时生成本地 playlist
        val segments = mutableListOf<Segment>()
        val keys = mutableMapOf<String, String>()   // 远程 key url -> 本地文件名
        val maps = mutableMapOf<String, String>()
        val out = StringBuilder()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-KEY") && line.contains("URI=") -> {
                    val remote = resolve(playlistUrl, uriAttr.find(line)!!.groupValues[1])
                    val local = keys.getOrPut(remote) { "key_${keys.size}.key" }
                    out.append(line.replace(uriAttr, "URI=\"$local\"")).append('\n')
                }
                line.startsWith("#EXT-X-MAP") && line.contains("URI=") -> {
                    val remote = resolve(playlistUrl, uriAttr.find(line)!!.groupValues[1])
                    val local = maps.getOrPut(remote) { "init_${maps.size}.mp4" }
                    out.append(line.replace(uriAttr, "URI=\"$local\"")).append('\n')
                }
                line.startsWith("#") -> out.append(line).append('\n')
                else -> {
                    val idx = segments.size
                    val remote = resolve(playlistUrl, line)
                    // fMP4（有 EXT-X-MAP 或 .m4s/.mp4 分片）保留原扩展名，否则播放器按 .ts 解析会失败
                    val ext = segExt(remote, maps.isNotEmpty())
                    segments += Segment(idx, remote, ext)
                    out.append(segName(idx, ext)).append('\n')
                }
            }
        }
        if (segments.isEmpty()) throw IOException("播放列表没有分片")

        // key 与 init 段
        (keys + maps).forEach { (remote, local) ->
            val f = File(dir, local)
            if (!f.exists() || f.length() == 0L) fetchToFile(remote, f)
        }

        val total = segments.size
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val queue = Channel<Segment>(Channel.UNLIMITED)
        segments.forEach { s ->
            val f = File(dir, segName(s.index, s.ext))
            if (f.exists() && f.length() > 0) { done.incrementAndGet(); bytes.addAndGet(f.length()) }
            else queue.trySend(s)
        }
        queue.close()
        onProgress(done.get(), total, bytes.get())

        coroutineScope {
            repeat(threads.coerceIn(1, 16)) {
                launch {
                    for (seg in queue) {
                        ensureActive()
                        val f = File(dir, segName(seg.index, seg.ext))
                        val n = fetchToFile(seg.url, f)
                        bytes.addAndGet(n)
                        onProgress(done.incrementAndGet(), total, bytes.get())
                    }
                }
            }
        }

        val index = File(dir, "index.m3u8")
        index.writeText(out.toString())
        index
    }

    private fun segName(i: Int, ext: String) = "seg_%05d.%s".format(i, ext)

    private fun segExt(url: String, hasMap: Boolean): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m4s") -> "m4s"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "mp4"
            hasMap -> "m4s"
            else -> "ts"
        }
    }

    /** 下载到 .tmp 后重命名，保证已存在的文件都是完整的；失败重试 5 次 */
    private suspend fun fetchToFile(url: String, f: File): Long {
        var lastErr: Exception? = null
        repeat(5) { attempt ->
            try {
                val tmp = File(f.path + ".tmp")
                val resp = Http.client.newCall(
                    Request.Builder().url(url).apply { headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }.build()
                ).await()
                resp.use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    val body = it.body ?: throw IOException("空响应")
                    tmp.outputStream().use { o -> body.byteStream().copyTo(o, 64 * 1024) }
                }
                if (!tmp.renameTo(f)) throw IOException("重命名失败")
                return f.length()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastErr = e
                delay(500L * (attempt + 1))
            }
        }
        throw IOException("分片下载失败：${lastErr?.message}")
    }
}

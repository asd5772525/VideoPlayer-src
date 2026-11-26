package com.vplayer.app.download

import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.await
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
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * MP4 多线程分块下载：按 4MB 分块并发 Range 请求写入同一文件的不同偏移，
 * 已完成分块记录在 chunks.state 中，重启后续传。服务器不支持 Range 时退化为单线程。
 */
class Mp4Downloader(
    private val dir: File,
    private val threads: Int,
    private val chunkSize: Long = CHUNK,
    private val onProgress: (bytes: Long, total: Long) -> Unit,
) {
    companion object { const val CHUNK = 4L * 1024 * 1024 }

    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val out = File(dir, "video.mp4")
        val (total, ranges) = probe(url)
        if (total <= 0 || !ranges) {
            singleStream(url, out)
            return@withContext out
        }
        val state = File(dir, "chunks.state")
        val doneChunks = if (state.exists()) state.readLines().mapNotNull { it.toIntOrNull() }.toMutableSet() else mutableSetOf()
        RandomAccessFile(out, "rw").use { if (it.length() != total) it.setLength(total) }
        val count = ((total + chunkSize - 1) / chunkSize).toInt()
        val bytes = AtomicLong(doneChunks.sumOf { chunkLen(it, total) })
        onProgress(bytes.get(), total)
        val queue = Channel<Int>(Channel.UNLIMITED)
        (0 until count).filterNot { it in doneChunks }.forEach { queue.trySend(it) }
        queue.close()
        val stateLock = Any()
        coroutineScope {
            repeat(threads.coerceIn(1, 16)) {
                launch {
                    for (c in queue) {
                        ensureActive()
                        val n = fetchChunk(url, out, c, total)
                        synchronized(stateLock) { state.appendText("$c\n") }
                        onProgress(bytes.addAndGet(n), total)
                    }
                }
            }
        }
        state.delete()
        out
    }

    private fun chunkLen(c: Int, total: Long) = minOf(chunkSize, total - c * chunkSize)

    private suspend fun probe(url: String): Pair<Long, Boolean> = try {
        Http.client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).await().use { r ->
            val cr = r.header("Content-Range") // bytes 0-0/12345
            val total = cr?.substringAfter('/')?.toLongOrNull() ?: r.body?.contentLength() ?: -1L
            total to (r.code == 206 && cr != null)
        }
    } catch (e: Exception) {
        -1L to false
    }

    private suspend fun fetchChunk(url: String, file: File, c: Int, total: Long): Long {
        val start = c * chunkSize
        val end = start + chunkLen(c, total) - 1
        var last: Exception? = null
        repeat(5) { attempt ->
            try {
                Http.client.newCall(Request.Builder().url(url).header("Range", "bytes=$start-$end").build()).await().use { r ->
                    if (r.code != 206) throw IOException("HTTP ${r.code}")
                    val input = r.body?.byteStream() ?: throw IOException("空响应")
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n
                        }
                        if (written != end - start + 1) throw IOException("分块不完整")
                    }
                }
                return end - start + 1
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                delay(500L * (attempt + 1))
            }
        }
        throw IOException("分块下载失败：${last?.message}")
    }

    private suspend fun singleStream(url: String, out: File) {
        Http.client.newCall(Request.Builder().url(url).build()).await().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("空响应")
            val total = body.contentLength()
            var read = 0L
            out.outputStream().use { o ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
    }
}

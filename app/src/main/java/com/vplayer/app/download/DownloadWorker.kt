package com.vplayer.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vplayer.app.App
import com.vplayer.app.R
import com.vplayer.app.data.db.DownloadEntity
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.await
import com.vplayer.app.player.PlayerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 长时间运行的下载协调 Worker（前台通知，切到后台 / 锁屏继续下载）：
 * 轮询下载队列，按“最大同时任务数”启动任务；任务被暂停 / 删除时取消对应协程。
 * App 重启后由 [DownloadRepository.resumePending] 重新拉起，已下载分片 / 分块自动跳过。
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val NAME = "offline-downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFY_ID = 1001
    }

    private val container = (context.applicationContext as App).container
    private val dao = container.db.downloadDao()

    private class Live(@Volatile var bytes: Long = 0, @Volatile var total: Long = 0,
                       @Volatile var segDone: Int = 0, @Volatile var segTotal: Int = 0)

    private val live = ConcurrentHashMap<String, Live>()

    override suspend fun doWork(): Result {
        runCatching { setForeground(foregroundInfo("准备下载…", 0)) }
        coroutineScope {
            val running = mutableMapOf<String, Job>()
            val lastBytes = mutableMapOf<String, Long>()
            var idleRounds = 0
            while (isActive) {
                val active = dao.byStatus(listOf(DownloadEntity.STATUS_QUEUED, DownloadEntity.STATUS_RUNNING))
                val activeIds = active.map { it.id }.toSet()
                // 取消被暂停 / 删除的任务，清理已结束的
                val it = running.entries.iterator()
                while (it.hasNext()) {
                    val (id, job) = it.next()
                    if (id !in activeIds) { job.cancel(); it.remove(); live.remove(id) }
                    else if (!job.isActive) { it.remove(); live.remove(id) }
                }
                val max = container.settings.current().maxDownloadTasks
                for (t in active) {
                    if (running.size >= max) break
                    if (t.id in running) continue
                    running[t.id] = launch { runTask(t) }
                }
                // 每秒把进度与速度写入数据库
                var totalSpeed = 0L
                for ((id, l) in live) {
                    val prev = lastBytes[id] ?: l.bytes
                    val speed = (l.bytes - prev).coerceAtLeast(0)
                    lastBytes[id] = l.bytes
                    totalSpeed += speed
                    dao.setProgress(id, l.bytes, l.total, l.segDone, l.segTotal, speed)
                }
                if (running.isEmpty() && active.isEmpty()) {
                    if (++idleRounds > 2) break
                } else idleRounds = 0
                runCatching {
                    setForeground(foregroundInfo("正在下载 ${running.size} 个任务 · ${formatSpeed(totalSpeed)}", running.size))
                }
                delay(1000)
            }
        }
        return Result.success()
    }

    private suspend fun runTask(t: DownloadEntity) {
        val l = Live()
        live[t.id] = l
        try {
            dao.setStatus(t.id, DownloadEntity.STATUS_RUNNING)
            val url = t.resolvedUrl.ifBlank { container.resolver.resolve(t.originUrl) }
            val kind = t.kind.ifBlank { detectKind(url) }
            dao.get(t.id)?.let { dao.update(it.copy(resolvedUrl = url, kind = kind, status = DownloadEntity.STATUS_RUNNING)) }
            val dir = container.downloads.dirFor(t.id)
            val threads = container.settings.current().downloadThreads
            val file = if (kind == "m3u8") {
                M3u8Downloader(dir, threads) { d, total, b -> l.segDone = d; l.segTotal = total; l.bytes = b }.download(url)
            } else {
                Mp4Downloader(dir, threads) { b, total -> l.bytes = b; l.total = total }.download(url)
            }
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            dao.get(t.id)?.let {
                dao.update(
                    it.copy(
                        status = DownloadEntity.STATUS_DONE, localPath = file.absolutePath,
                        downloadedBytes = size, totalBytes = size, segDone = l.segTotal, segTotal = l.segTotal,
                        speedBps = 0, error = "",
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.setStatus(t.id, DownloadEntity.STATUS_FAILED, e.message ?: "下载失败")
        } finally {
            live.remove(t.id)
        }
    }

    private suspend fun detectKind(url: String): String {
        if (PlayerManager.isHlsUrl(url)) return "m3u8"
        if (url.lowercase().contains(".mp4")) return "mp4"
        return try {
            Http.client.newCall(Request.Builder().url(url).header("Range", "bytes=0-511").build()).await().use { r ->
                val ct = r.header("Content-Type").orEmpty().lowercase()
                val head = r.body?.source()?.let { s -> s.request(7); s.buffer.snapshot(minOf(7L, s.buffer.size).toInt()).utf8() } ?: ""
                if (ct.contains("mpegurl") || head.startsWith("#EXTM3U")) "m3u8" else "mp4"
            }
        } catch (e: Exception) {
            "mp4"
        }
    }

    private fun formatSpeed(bps: Long): String = when {
        bps > 1024 * 1024 -> "%.1f MB/s".format(bps / 1024f / 1024f)
        else -> "${bps / 1024} KB/s"
    }

    private fun foregroundInfo(text: String, count: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "离线下载", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("离线下载")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, count > 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFY_ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("离线下载中", 1)
}

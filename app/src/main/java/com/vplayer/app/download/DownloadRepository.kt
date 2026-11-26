package com.vplayer.app.download

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vplayer.app.data.db.AppDatabase
import com.vplayer.app.data.db.DownloadEntity
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import com.vplayer.app.data.model.vodKey
import com.vplayer.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class StorageInfo(val usedByApp: Long, val free: Long, val total: Long)

class DownloadRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val dao = db.downloadDao()
    val all: Flow<List<DownloadEntity>> = dao.observeAll()

    val root: File by lazy {
        (context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")).apply { mkdirs() }
    }

    fun dirFor(id: String): File = File(root, md5(id))

    fun taskId(vodKey: String, epIndex: Int) = "$vodKey#$epIndex"

    suspend fun enqueue(source: Source, vod: Vod, lineIndex: Int, epIndices: List<Int>) {
        val line = vod.lines.getOrNull(lineIndex) ?: return
        val key = vodKey(source.id, vod.id)
        for (i in epIndices) {
            val ep = line.episodes.getOrNull(i) ?: continue
            dao.insert(
                DownloadEntity(
                    id = taskId(key, i), vodKey = key, sourceId = source.id, vodId = vod.id,
                    name = vod.name, epIndex = i, epName = ep.name, pic = vod.pic, originUrl = ep.url,
                )
            )
        }
        ensureRunning()
    }

    suspend fun pause(id: String) = dao.setStatus(id, DownloadEntity.STATUS_PAUSED)

    suspend fun resume(id: String) {
        dao.setStatus(id, DownloadEntity.STATUS_QUEUED)
        ensureRunning()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        dirFor(id).deleteRecursively()
    }

    suspend fun completedFile(vodKey: String, epIndex: Int): String? {
        val e = dao.get(taskId(vodKey, epIndex)) ?: return null
        if (e.status != DownloadEntity.STATUS_DONE || e.localPath.isBlank()) return null
        return e.localPath.takeIf { File(it).exists() }
    }

    fun ensureRunning() {
        val wifiOnly = settings.state.value.wifiOnly
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DownloadWorker.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** App 启动时调用：有未完成任务则恢复 */
    suspend fun resumePending() {
        val pending = dao.byStatus(listOf(DownloadEntity.STATUS_QUEUED, DownloadEntity.STATUS_RUNNING))
        if (pending.isNotEmpty()) ensureRunning()
    }

    suspend fun storage(): StorageInfo = withContext(Dispatchers.IO) {
        val used = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val st = StatFs(root.path)
        StorageInfo(used, st.availableBytes, st.totalBytes)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

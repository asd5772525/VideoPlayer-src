package com.vplayer.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 用户在设置中自行添加的本地数据源（内置源来自 config.json，只读） */
@Entity(tableName = "local_source")
data class LocalSourceEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val name: String,
    val api: String,
    val type: String = "json",
    val enabled: Boolean = true,
    val sort: Int = 0,
)

@Entity(tableName = "local_parse")
data class LocalParseEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val name: String,
    val url: String,
    val type: String = "json",
    val enabled: Boolean = true,
    val sort: Int = 0,
)

/** API 响应缓存（stale-while-revalidate） */
@Entity(tableName = "api_cache")
data class ApiCacheEntity(
    @PrimaryKey val key: String,
    val body: String,
    val time: Long,
)

/** 每一集的播放进度 */
@Entity(tableName = "progress", primaryKeys = ["vodKey", "epIndex"])
data class ProgressEntity(
    val vodKey: String,
    val epIndex: Int,
    val epName: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
)

/** 观看历史（每部影片一条，记录最近一集） */
@Entity(tableName = "history", indices = [Index("updatedAt")])
data class HistoryEntity(
    @PrimaryKey val vodKey: String,
    val sourceId: String,
    val vodId: String,
    val name: String,
    val pic: String,
    val lineIndex: Int,
    val epIndex: Int,
    val epName: String,
    val epCount: Int,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
)

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val vodKey: String,
    val sourceId: String,
    val vodId: String,
    val name: String,
    val pic: String,
    val remarks: String,
    val epCount: Int,
    val latestEpCount: Int,
    val hasUpdate: Boolean = false,
    val createdAt: Long,
)

/** 每部剧单独的跳过片头片尾 */
@Entity(tableName = "show_pref")
data class ShowPrefEntity(
    @PrimaryKey val vodKey: String,
    val introSec: Int,
    val outroSec: Int,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    val time: Long,
)

@Entity(tableName = "download")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val vodKey: String,
    val sourceId: String,
    val vodId: String,
    val name: String,
    val epIndex: Int,
    val epName: String,
    val pic: String,
    val originUrl: String,
    val resolvedUrl: String = "",
    val kind: String = "",          // m3u8 / mp4
    val status: Int = STATUS_QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val segDone: Int = 0,
    val segTotal: Int = 0,
    val speedBps: Long = 0,
    val localPath: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = when {
            segTotal > 0 -> segDone.toFloat() / segTotal
            totalBytes > 0 -> downloadedBytes.toFloat() / totalBytes
            else -> 0f
        }

    companion object {
        const val STATUS_QUEUED = 0
        const val STATUS_RUNNING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_DONE = 3
        const val STATUS_FAILED = 4
    }
}

/**
 * 源 / 解析接口 健康度（后台并发测速结果）。
 * 连续失败 3 次自动移除：本地源从表中删除（信息保留在此以便恢复），内置源仅本地隐藏。
 */
@Entity(tableName = "health")
data class HealthEntity(
    @PrimaryKey val id: String,        // Source.id / ParseApi.id
    val kind: String,                  // source / parse
    val name: String,
    val url: String,
    val type: String = "json",
    val builtIn: Boolean,
    val latencyMs: Long = -1,          // -1 未测 / 失败
    val failCount: Int = 0,
    val lastCheck: Long = 0,
    val removed: Boolean = false,
) {
    companion object {
        const val KIND_SOURCE = "source"
        const val KIND_PARSE = "parse"
        const val MAX_FAIL = 3
    }
}

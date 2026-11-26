package com.vplayer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM local_source ORDER BY sort, rowId")
    fun observeSources(): Flow<List<LocalSourceEntity>>

    @Insert
    suspend fun insertSource(e: LocalSourceEntity): Long

    @Update
    suspend fun updateSource(e: LocalSourceEntity)

    @Query("DELETE FROM local_source WHERE rowId = :rowId")
    suspend fun deleteSource(rowId: Long)

    @Query("SELECT COUNT(*) FROM local_source WHERE api = :api")
    suspend fun countSourceByApi(api: String): Int

    @Query("SELECT * FROM local_parse ORDER BY sort, rowId")
    fun observeParses(): Flow<List<LocalParseEntity>>

    @Insert
    suspend fun insertParse(e: LocalParseEntity): Long

    @Update
    suspend fun updateParse(e: LocalParseEntity)

    @Query("DELETE FROM local_parse WHERE rowId = :rowId")
    suspend fun deleteParse(rowId: Long)

    @Query("SELECT COUNT(*) FROM local_parse WHERE url = :url")
    suspend fun countParseByUrl(url: String): Int
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM api_cache WHERE `key` = :key")
    suspend fun get(key: String): ApiCacheEntity?

    @Upsert
    suspend fun put(e: ApiCacheEntity)

    @Query("DELETE FROM api_cache WHERE time < :before")
    suspend fun trim(before: Long)

    @Query("DELETE FROM api_cache")
    suspend fun clear()
}

@Dao
interface PlaybackDao {
    @Upsert
    suspend fun saveProgress(e: ProgressEntity)

    @Query("SELECT * FROM progress WHERE vodKey = :vodKey AND epIndex = :ep")
    suspend fun progress(vodKey: String, ep: Int): ProgressEntity?

    @Query("SELECT * FROM progress WHERE vodKey = :vodKey")
    fun observeProgress(vodKey: String): Flow<List<ProgressEntity>>

    @Upsert
    suspend fun saveHistory(e: HistoryEntity)

    @Query("SELECT * FROM history WHERE vodKey = :vodKey")
    suspend fun history(vodKey: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE vodKey = :vodKey")
    fun observeHistory(vodKey: String): Flow<HistoryEntity?>

    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT :limit")
    fun observeHistoryList(limit: Int = 200): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE vodKey = :vodKey")
    suspend fun deleteHistory(vodKey: String)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("SELECT * FROM show_pref WHERE vodKey = :vodKey")
    suspend fun showPref(vodKey: String): ShowPrefEntity?

    @Upsert
    suspend fun saveShowPref(e: ShowPrefEntity)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite")
    suspend fun all(): List<FavoriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE vodKey = :vodKey)")
    fun observeIsFav(vodKey: String): Flow<Boolean>

    @Query("SELECT * FROM favorite WHERE vodKey = :vodKey")
    suspend fun get(vodKey: String): FavoriteEntity?

    @Upsert
    suspend fun upsert(e: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE vodKey = :vodKey")
    suspend fun delete(vodKey: String)
}

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_history ORDER BY time DESC LIMIT 20")
    fun observe(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE status IN (:statuses) ORDER BY createdAt")
    suspend fun byStatus(statuses: List<Int>): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT * FROM download WHERE vodKey = :vodKey AND status = 3")
    fun observeCompletedFor(vodKey: String): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: DownloadEntity): Long

    @Update
    suspend fun update(e: DownloadEntity)

    @Query("UPDATE download SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, status: Int, error: String = "")

    @Query("UPDATE download SET downloadedBytes = :bytes, totalBytes = :total, segDone = :segDone, segTotal = :segTotal, speedBps = :speed WHERE id = :id")
    suspend fun setProgress(id: String, bytes: Long, total: Long, segDone: Int, segTotal: Int, speed: Long)

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface HealthDao {
    @Query("SELECT * FROM health")
    fun observeAll(): Flow<List<HealthEntity>>

    @Query("SELECT * FROM health")
    suspend fun all(): List<HealthEntity>

    @Query("SELECT * FROM health WHERE id = :id")
    suspend fun get(id: String): HealthEntity?

    @Upsert
    suspend fun upsert(e: HealthEntity)

    @Query("DELETE FROM health WHERE id = :id")
    suspend fun delete(id: String)
}

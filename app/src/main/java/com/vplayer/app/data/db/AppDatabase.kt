package com.vplayer.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalSourceEntity::class, LocalParseEntity::class, ApiCacheEntity::class,
        ProgressEntity::class, HistoryEntity::class, FavoriteEntity::class,
        ShowPrefEntity::class, SearchHistoryEntity::class, DownloadEntity::class, HealthEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun cacheDao(): CacheDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchDao(): SearchDao
    abstract fun downloadDao(): DownloadDao
    abstract fun healthDao(): HealthDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vplayer.db")
                .fallbackToDestructiveMigration()
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

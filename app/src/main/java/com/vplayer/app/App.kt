package com.vplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.vplayer.app.data.net.Http
import kotlinx.coroutines.launch

class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        container = AppContainer(this)
        // 非关键路径：恢复未完成下载、清理过期缓存
        container.appScope.launch {
            runCatching { container.downloads.resumePending() }
            runCatching { container.db.cacheDao().trim(System.currentTimeMillis() - 7L * 24 * 3600_000) }
        }
    }

    /** Coil：内存缓存 25% + 256MB 磁盘缓存，复用全局 OkHttp 连接池 */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client.newBuilder().cache(null).build() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}

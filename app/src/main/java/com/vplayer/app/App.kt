package com.vplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.vplayer.app.data.net.Http
import com.vplayer.app.util.AppLog
import com.vplayer.app.util.CrashLog
import com.vplayer.app.data.net.Warmup
import com.vplayer.app.player.engine.PlaybackProfiles
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 先装崩溃日志，后续初始化里的崩溃也能记下来
        CrashLog.install(this)
        AppLog.init(this)
        Http.init(this)
        container = AppContainer(this)
        // 非关键路径：恢复未完成下载、清理过期缓存
        // 连接预热（v1.2）：默认源 + 前两个通用解析，DNS + TLS 握手提前做掉，首页 / 起播少等一次握手
        container.appScope.launch {
            runCatching {
                val s = container.settings.current()
                if (PlaybackProfiles.of(s).dnsPrefetch) {
                    val src = withTimeoutOrNull(3_000) { container.sources.current.filterNotNull().first() }
                    val parses = container.sources.parses.value.map { it.parse }.filter { it.ownerId.isEmpty() }.take(2).map { it.url }
                    Warmup.warm(listOfNotNull(src?.api) + parses)
                }
            }
        }
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

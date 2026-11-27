package com.vplayer.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vplayer.app.data.config.PlayerDefaults
import com.vplayer.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val currentSourceId: String = "",
    val directFirst: Boolean = true,
    val autoSpeedTest: Boolean = true,
    val hwDecode: Boolean = true,
    val preloadNext: Boolean = true,
    val bufferSeconds: Int = 30,
    val rememberProgress: Boolean = true,
    val autoNext: Boolean = true,
    val defaultSpeed: Float = 1f,
    val longPressSpeed: Float = 3f,
    val skipIntro: Int = 0,
    val skipOutro: Int = 0,
    val downloadThreads: Int = 8,
    val maxDownloadTasks: Int = 2,
    val wifiOnly: Boolean = false,
    val cacheSizeMb: Int = 512,
    val theme: Int = 0, // 0 跟随系统 1 深色 2 浅色
    val updateUrlOverride: String = "",
    val appLock: Boolean = false,
    val appLockPin: String = "",
    val dismissedNotice: String = "",
    val lastUpdateCheck: Long = 0,
    val lastHealthCheck: Long = 0,
    val adFilter: Boolean = true,      // 去除插播广告（M3U8）
    val autoFailover: Boolean = true,  // 播放失败 / 卡顿时自动换线
    // ---- v1.2 播放内核与加载策略 ----
    val kernel: String = "auto",        // auto / exo / ijk / system
    val preset: String = "balanced",    // fast / balanced / smooth / custom
    val minBufferSeconds: Int = 15,     // 以下为「自定义」时的值（加上 bufferSeconds / preloadNext / cacheSizeMb）
    val startMs: Int = 1000,
    val rebufferMs: Int = 2000,
    val connectTimeoutSec: Int = 8,
    val readTimeoutSec: Int = 15,
    val dnsPrefetch: Boolean = true,
    val parseRace: Boolean = true,      // 解析竞速（前两个同时请求）
)

/**
 * DataStore 设置。默认值来自打包进 APK 的 config.json 的 player 段，
 * 用户在设置中修改后以用户值为准。
 */
class SettingsRepository(private val context: Context, scope: CoroutineScope, private val defaults: PlayerDefaults) {

    private object K {
        val currentSource = stringPreferencesKey("current_source")
        val directFirst = booleanPreferencesKey("direct_first")
        val autoSpeedTest = booleanPreferencesKey("auto_speed_test")
        val hwDecode = booleanPreferencesKey("hw_decode")
        val preloadNext = booleanPreferencesKey("preload_next")
        val bufferSeconds = intPreferencesKey("buffer_seconds")
        val rememberProgress = booleanPreferencesKey("remember_progress")
        val autoNext = booleanPreferencesKey("auto_next")
        val defaultSpeed = floatPreferencesKey("default_speed")
        val longPressSpeed = floatPreferencesKey("long_press_speed")
        val skipIntro = intPreferencesKey("skip_intro")
        val skipOutro = intPreferencesKey("skip_outro")
        val downloadThreads = intPreferencesKey("download_threads")
        val maxDownloadTasks = intPreferencesKey("max_download_tasks")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val cacheSizeMb = intPreferencesKey("cache_size_mb")
        val theme = intPreferencesKey("theme")
        val updateUrl = stringPreferencesKey("update_url")
        val appLock = booleanPreferencesKey("app_lock")
        val appLockPin = stringPreferencesKey("app_lock_pin")
        val dismissedNotice = stringPreferencesKey("dismissed_notice")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val lastHealthCheck = longPreferencesKey("last_health_check")
        val adFilter = booleanPreferencesKey("ad_filter")
        val autoFailover = booleanPreferencesKey("auto_failover")
        val kernel = stringPreferencesKey("kernel")
        val preset = stringPreferencesKey("preset")
        val minBufferSeconds = intPreferencesKey("min_buffer_seconds")
        val startMs = intPreferencesKey("start_ms")
        val rebufferMs = intPreferencesKey("rebuffer_ms")
        val connectTimeoutSec = intPreferencesKey("connect_timeout_sec")
        val readTimeoutSec = intPreferencesKey("read_timeout_sec")
        val dnsPrefetch = booleanPreferencesKey("dns_prefetch")
        val parseRace = booleanPreferencesKey("parse_race")
    }

    private val d = defaults

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            currentSourceId = p[K.currentSource] ?: "",
            directFirst = p[K.directFirst] ?: d.directFirst,
            autoSpeedTest = p[K.autoSpeedTest] ?: d.autoSpeedTest,
            hwDecode = p[K.hwDecode] ?: d.hwDecode,
            preloadNext = p[K.preloadNext] ?: d.preloadNext,
            bufferSeconds = p[K.bufferSeconds] ?: d.bufferSeconds,
            rememberProgress = p[K.rememberProgress] ?: true,
            autoNext = p[K.autoNext] ?: d.autoNext,
            defaultSpeed = p[K.defaultSpeed] ?: d.defaultSpeed,
            longPressSpeed = p[K.longPressSpeed] ?: d.longPressSpeed,
            skipIntro = p[K.skipIntro] ?: d.skipIntro,
            skipOutro = p[K.skipOutro] ?: d.skipOutro,
            downloadThreads = (p[K.downloadThreads] ?: d.downloadConcurrency).coerceIn(1, 16),
            maxDownloadTasks = (p[K.maxDownloadTasks] ?: d.maxDownloadTasks).coerceIn(1, 5),
            wifiOnly = p[K.wifiOnly] ?: false,
            cacheSizeMb = p[K.cacheSizeMb] ?: d.cacheMb,
            theme = p[K.theme] ?: 0,
            updateUrlOverride = p[K.updateUrl] ?: "",
            appLock = p[K.appLock] ?: false,
            appLockPin = p[K.appLockPin] ?: "",
            dismissedNotice = p[K.dismissedNotice] ?: "",
            lastUpdateCheck = p[K.lastUpdateCheck] ?: 0L,
            lastHealthCheck = p[K.lastHealthCheck] ?: 0L,
            adFilter = p[K.adFilter] ?: true,
            autoFailover = p[K.autoFailover] ?: true,
            kernel = p[K.kernel] ?: d.kernel,
            // v1.1 升级上来、改过缓冲 / 预加载 / 缓存的用户：默认进入「自定义」，保留他们原来的值
            preset = p[K.preset] ?: if (p[K.bufferSeconds] != null || p[K.preloadNext] != null || p[K.cacheSizeMb] != null) "custom" else d.preset,
            minBufferSeconds = p[K.minBufferSeconds] ?: d.minBufferSeconds,
            startMs = p[K.startMs] ?: d.startMs,
            rebufferMs = p[K.rebufferMs] ?: d.rebufferMs,
            connectTimeoutSec = p[K.connectTimeoutSec] ?: d.connectTimeout,
            readTimeoutSec = p[K.readTimeoutSec] ?: d.readTimeout,
            dnsPrefetch = p[K.dnsPrefetch] ?: d.dnsPrefetch,
            parseRace = p[K.parseRace] ?: d.parseRace,
        )
    }

    private val _loaded = MutableStateFlow(false)
    /** DataStore 是否已经读到真实值（之前 [state] 只是默认值）。应用锁要等它为 true 才决定显示什么，避免闪出首页 */
    val loaded: StateFlow<Boolean> = _loaded

    val state: StateFlow<AppSettings> = flow
        .catch { e -> AppLog.e("settings", "读取设置失败，使用默认值", e); emit(AppSettings()) }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings(
        directFirst = d.directFirst, autoSpeedTest = d.autoSpeedTest, hwDecode = d.hwDecode,
        preloadNext = d.preloadNext, bufferSeconds = d.bufferSeconds, autoNext = d.autoNext,
        defaultSpeed = d.defaultSpeed, longPressSpeed = d.longPressSpeed, skipIntro = d.skipIntro,
        skipOutro = d.skipOutro, downloadThreads = d.downloadConcurrency, maxDownloadTasks = d.maxDownloadTasks,
        cacheSizeMb = d.cacheMb, kernel = d.kernel, preset = d.preset, minBufferSeconds = d.minBufferSeconds,
        startMs = d.startMs, rebufferMs = d.rebufferMs, connectTimeoutSec = d.connectTimeout, readTimeoutSec = d.readTimeout,
        dnsPrefetch = d.dnsPrefetch, parseRace = d.parseRace,
    ))

    suspend fun current(): AppSettings = flow.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setCurrentSource(v: String) = set(K.currentSource, v)
    suspend fun setDirectFirst(v: Boolean) = set(K.directFirst, v)
    suspend fun setAutoSpeedTest(v: Boolean) = set(K.autoSpeedTest, v)
    suspend fun setHwDecode(v: Boolean) = set(K.hwDecode, v)
    suspend fun setPreloadNext(v: Boolean) = set(K.preloadNext, v)
    suspend fun setBufferSeconds(v: Int) = set(K.bufferSeconds, v)
    suspend fun setRememberProgress(v: Boolean) = set(K.rememberProgress, v)
    suspend fun setAutoNext(v: Boolean) = set(K.autoNext, v)
    suspend fun setDefaultSpeed(v: Float) = set(K.defaultSpeed, v)
    suspend fun setLongPressSpeed(v: Float) = set(K.longPressSpeed, v)
    suspend fun setSkipIntro(v: Int) = set(K.skipIntro, v)
    suspend fun setSkipOutro(v: Int) = set(K.skipOutro, v)
    suspend fun setDownloadThreads(v: Int) = set(K.downloadThreads, v.coerceIn(1, 16))
    suspend fun setMaxDownloadTasks(v: Int) = set(K.maxDownloadTasks, v.coerceIn(1, 5))
    suspend fun setWifiOnly(v: Boolean) = set(K.wifiOnly, v)
    suspend fun setCacheSizeMb(v: Int) = set(K.cacheSizeMb, v)
    suspend fun setTheme(v: Int) = set(K.theme, v)
    suspend fun setUpdateUrl(v: String) = set(K.updateUrl, v.trim())
    suspend fun setAppLock(enabled: Boolean, pin: String) {
        context.dataStore.edit { it[K.appLock] = enabled; it[K.appLockPin] = pin }
    }
    suspend fun setDismissedNotice(v: String) = set(K.dismissedNotice, v)
    suspend fun setLastUpdateCheck(v: Long) = set(K.lastUpdateCheck, v)
    suspend fun setLastHealthCheck(v: Long) = set(K.lastHealthCheck, v)
    suspend fun setAdFilter(v: Boolean) = set(K.adFilter, v)
    suspend fun setAutoFailover(v: Boolean) = set(K.autoFailover, v)
    suspend fun setKernel(v: String) = set(K.kernel, v)
    suspend fun setPreset(v: String) = set(K.preset, v)
    suspend fun setMinBufferSeconds(v: Int) = set(K.minBufferSeconds, v.coerceIn(1, 120))
    suspend fun setStartMs(v: Int) = set(K.startMs, v.coerceIn(100, 10_000))
    suspend fun setRebufferMs(v: Int) = set(K.rebufferMs, v.coerceIn(100, 20_000))
    suspend fun setConnectTimeoutSec(v: Int) = set(K.connectTimeoutSec, v.coerceIn(2, 60))
    suspend fun setReadTimeoutSec(v: Int) = set(K.readTimeoutSec, v.coerceIn(3, 120))
    suspend fun setDnsPrefetch(v: Boolean) = set(K.dnsPrefetch, v)
    suspend fun setParseRace(v: Boolean) = set(K.parseRace, v)
}

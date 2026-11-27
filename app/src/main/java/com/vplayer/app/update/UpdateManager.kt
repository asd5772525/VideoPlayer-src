package com.vplayer.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vplayer.app.BuildConfig
import com.vplayer.app.data.config.AppConfig
import com.vplayer.app.data.model.UpdateInfo
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.await
import com.vplayer.app.data.net.getString
import com.vplayer.app.data.prefs.SettingsRepository
import com.vplayer.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateState
}

/** 纯逻辑（便于单元测试）：解析 update.json、比较版本、校验 MD5 */
object UpdateLogic {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(body: String): UpdateInfo = json.decodeFromString(UpdateInfo.serializer(), body.trimStart('\uFEFF'))

    /** 只有 versionCode 更大且有下载地址才算新版本 */
    fun isNewer(info: UpdateInfo, currentCode: Int): Boolean =
        info.versionCode > currentCode && (info.apkUrl.isNotBlank() || info.apks.values.any { it.url.isNotBlank() })

    /** 选中的安装包 */
    data class Pick(val abi: String, val url: String, val md5: String, val size: String)

    /**
     * 按设备支持的 ABI（Build.SUPPORTED_ABIS，按优先级）挑最合适的分包；都没有就用 universal（顶层 apkUrl）。
     * 旧格式（没有 apks）直接返回顶层字段。
     */
    fun pickApk(info: UpdateInfo, supportedAbis: List<String>): Pick {
        for (abi in supportedAbis) {
            val v = info.apks[abi] ?: continue
            if (v.url.isNotBlank()) return Pick(abi, v.url, v.md5, v.size)
        }
        if (info.apkUrl.isNotBlank()) return Pick("universal", info.apkUrl, info.md5, info.size)
        val u = info.apks["universal"]
        if (u != null && u.url.isNotBlank()) return Pick("universal", u.url, u.md5, u.size)
        return Pick("universal", "", info.md5, info.size)
    }

    fun md5(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** md5 为空时不校验（兼容手写的 update.json） */
    fun md5Matches(f: File, expected: String): Boolean = expected.isBlank() || md5(f).equals(expected.trim(), true)
}

/**
 * OTA 更新：读取 update.json（依次尝试：设置中的覆盖地址 → config.json 的 updateUrl → BuildConfig.UPDATE_URL 列表，
 * 支持 raw.githubusercontent / jsDelivr 等镜像），带防缓存参数。
 * 永远不在启动关键路径：由 MainActivity 在首帧后延迟数秒、每天至多一次后台检查。
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val config: AppConfig,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    fun urls(): List<String> {
        val override = settings.state.value.updateUrlOverride
        return (listOf(override, config.updateUrl) + BuildConfig.UPDATE_URL.split(','))
            .map { it.trim() }.filter { it.startsWith("http") }.distinct()
    }

    /** @return 有新版本返回信息；manual=true 时无新版本也会更新状态 */
    suspend fun check(manual: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!manual) {
            val last = settings.current().lastUpdateCheck
            if (System.currentTimeMillis() - last < 24 * 3600_000L) return@withContext null
        }
        val list = urls()
        if (list.isEmpty()) {
            if (manual) _state.value = UpdateState.Failed(null, "未配置更新地址")
            return@withContext null
        }
        var info: UpdateInfo? = null
        var err: String? = null
        for (u in list) {
            try {
                val bust = (if (u.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis() / 60_000
                info = UpdateLogic.parse(Http.client.getString(u + bust))
                break
            } catch (e: Exception) {
                err = e.message
            }
        }
        // 只有成功读取到 update.json 才记录检查时间；网络失败时下次启动会重试，而不是等 24 小时
        if (info != null) settings.setLastUpdateCheck(System.currentTimeMillis())
        val i = info
        when {
            i == null -> { if (manual) _state.value = UpdateState.Failed(null, "检查失败：${err ?: "网络错误"}"); null }
            UpdateLogic.isNewer(i, BuildConfig.VERSION_CODE) -> { _state.value = UpdateState.Available(i); i }
            else -> { if (manual) _state.value = UpdateState.Failed(null, "已是最新版本 v${BuildConfig.VERSION_NAME}"); null }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        try {
            _state.value = UpdateState.Downloading(info, 0f)
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val pick = UpdateLogic.pickApk(info, Build.SUPPORTED_ABIS.toList())
            AppLog.i("update", "下载 v${info.versionName} 安装包：${pick.abi}")
            val file = File(dir, "update-${info.versionCode}-${pick.abi}.apk")
            if (!(file.exists() && pick.md5.isNotBlank() && UpdateLogic.md5Matches(file, pick.md5))) {
                Http.client.newCall(Request.Builder().url(pick.url).build()).await().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("空响应")
                    val total = body.contentLength()
                    var read = 0L
                    var lastEmit = 0L
                    file.outputStream().use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastEmit > 200) {
                                lastEmit = now
                                _state.value = UpdateState.Downloading(info, read.toFloat() / total)
                            }
                        }
                    }
                }
                if (!UpdateLogic.md5Matches(file, pick.md5)) {
                    file.delete()
                    throw IOException("MD5 校验失败，安装包可能已损坏")
                }
            }
            _state.value = UpdateState.ReadyToInstall(info, file)
            withContext(Dispatchers.Main) { install(file) }
        } catch (e: Exception) {
            AppLog.e("update", "更新下载失败", e)
            _state.value = UpdateState.Failed(info, e.message ?: "下载失败")
        }
    }

    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

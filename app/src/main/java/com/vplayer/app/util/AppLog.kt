package com.vplayer.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 轻量的应用内日志：只记播放 / 解析 / 下载 / 更新相关的错误与关键事件。
 * - 内存里保留最近 400 条（导出时也带上）
 * - 后台单线程追加写入 files/logs/app.log，超过 512KB 轮转为 app.1.log
 * - 不依赖 Context 也能调用（未初始化时只记内存），所以纯 JVM 单元测试里也安全
 */
object AppLog {
    private const val MAX_LINES = 400
    private const val MAX_FILE = 512 * 1024L
    private val ring = ArrayDeque<String>(MAX_LINES)
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } } }
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    var dir: File? = null
        private set

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(fmt.get()!!.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        synchronized(ring) {
            if (ring.size >= MAX_LINES) ring.removeFirst()
            ring.addLast(line)
        }
        val d = dir ?: return
        runCatching {
            io.execute {
                runCatching {
                    val f = File(d, "app.log")
                    if (f.length() > MAX_FILE) f.renameTo(File(d, "app.1.log"))
                    f.appendText(line + "\n")
                }
            }
        }
    }

    fun deviceInfo(versionName: String, versionCode: Int): String = "App v$versionName ($versionCode) · " + runCatching {
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · " +
            "ABI ${Build.SUPPORTED_ABIS?.joinToString().orEmpty()}"
    }.getOrDefault("Android ?")
}

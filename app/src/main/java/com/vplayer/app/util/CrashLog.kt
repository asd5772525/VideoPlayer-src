package com.vplayer.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.vplayer.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志：UncaughtExceptionHandler 把堆栈写到 files/logs/crash-时间.txt（只保留最近 10 个），
 * 然后交还给系统默认处理（照常弹出“应用已停止”）。不上传任何数据。
 */
object CrashLog {
    const val KEEP = 10

    fun install(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(dir, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun write(dir: File, thread: Thread, error: Throwable): File {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val f = File(dir, "crash-$ts.txt")
        f.writeText(
            buildString {
                append("时间: ").append(ts).append('\n')
                append("线程: ").append(thread.name).append('\n')
                append(AppLog.deviceInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)).append("\n\n")
                append(sw.toString())
                append("\n最近日志:\n")
                AppLog.recent().takeLast(60).forEach { append(it).append('\n') }
            }
        )
        prune(dir, KEEP)
        return f
    }

    /** 只保留最新的 [keep] 个崩溃文件 */
    fun prune(dir: File, keep: Int) {
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    fun crashFiles(context: Context): List<File> =
        File(context.filesDir, "logs").listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    /** 打包为一个 txt（设备信息 + 崩溃 + 运行日志），通过 FileProvider 分享 */
    fun export(context: Context): Intent {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val out = File(context.cacheDir, "logs").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(out, "vplayer-log-$ts.txt")
        f.bufferedWriter().use { w ->
            w.append("# 影视 App 日志导出 $ts\n").append(AppLog.deviceInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)).append("\n\n")
            val crashes = crashFiles(context)
            w.append("## 崩溃记录（${crashes.size} 个，最多保留 $KEEP 个）\n")
            crashes.forEach { c -> w.append("\n----- ").append(c.name).append(" -----\n").append(c.readText()) }
            w.append("\n## 运行日志\n")
            listOf("app.1.log", "app.log").map { File(dir, it) }.filter { it.exists() }.forEach { w.append(it.readText()) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "影视 App 日志 $ts")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "导出日志").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

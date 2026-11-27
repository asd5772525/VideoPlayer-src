package com.vplayer.app

import com.vplayer.app.data.cms.HealthPolicy
import com.vplayer.app.data.cms.MacCms
import com.vplayer.app.data.cms.buildSourceItems
import com.vplayer.app.data.db.HealthEntity
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.net.Http
import com.vplayer.app.data.net.getString
import com.vplayer.app.download.M3u8Downloader
import com.vplayer.app.download.Mp4Downloader
import com.vplayer.app.update.UpdateLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 针对本地模拟苹果CMS 服务器（sim/mock/mock_maccms.py）的集成测试。
 * 运行：MOCK_BASE=http://127.0.0.1:8765 ./gradlew testDebugUnitTest ；未设置或服务器未启动时自动跳过。
 */
class MockServerTest {
    companion object {
        val base: String = System.getProperty("mock.base").orEmpty().trimEnd('/')

        @BeforeClass
        @JvmStatic
        fun setup() {
            Http.installForTest(OkHttpClient())
            MacCms.xmlParserFactory = { org.kxml2.io.KXmlParser() }
        }

        fun reachable(): Boolean = base.isNotEmpty() && runCatching {
            (URL("$base/health").openConnection() as HttpURLConnection).run { connectTimeout = 1000; responseCode == 200 }
        }.getOrDefault(false)

        fun raw(path: String): ByteArray = URL("$base$path").readBytes()
        fun stats(): Map<String, Int> = Regex("\"([^\"]+)\": (\\d+)").findAll(String(raw("/__stats")))
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        fun ctl(q: String) { raw("/__ctl?$q") }
    }

    private fun need() = assumeTrue("mock server not running (set MOCK_BASE)", reachable())
    private fun tmpDir(): File = Files.createTempDirectory("dl").toFile()
    private suspend fun get(url: String) = Http.client.getString(url)

    @Test
    fun jsonListDetailSearch(): Unit = runBlocking {
        need()
        val api = "$base/good/api.php/provide/vod"
        val list = MacCms.parse(get(MacCms.listUrl(api)))
        assertEquals(listOf("电影", "剧集", "动漫"), list.categories.map { it.name })
        assertEquals(3, list.pageCount)
        assertEquals(10, list.list.size)
        val page2 = MacCms.parse(get(MacCms.detailListUrl(api, 2, typeId = "2")))
        assertTrue(page2.list.all { it.typeId == "2" })
        val d = MacCms.parse(get(MacCms.detailUrl(api, listOf("5", "6")))).list
        assertEquals(listOf("5", "6"), d.map { it.id })
        assertEquals(listOf("m3u8", "mp4", "aes"), d[0].lines.map { it.name })
        assertEquals(3, d[0].lines[0].episodes.size)
        assertEquals("第02集", d[0].lines[0].episodes[1].name)
        assertTrue(d[0].lines[1].episodes[0].url.endsWith("/media/sample.mp4"))
        assertEquals("这是第 5 部示例影片的简介 内容", d[0].content)
        val s = MacCms.parse(get(MacCms.detailListUrl(api, 1, null, "影片07"))).list
        assertEquals(listOf("示例影片07"), s.map { it.name })
        val s2 = MacCms.parse(get(MacCms.searchListUrl(api, "影片1"))).list
        assertEquals(10, s2.size)
    }

    @Test
    fun xmlListDetail(): Unit = runBlocking {
        need()
        val api = "$base/good/api.php/provide/vod/at/xml"
        val list = MacCms.parse(get(MacCms.listUrl(api)))
        assertEquals(3, list.categories.size)
        assertEquals(3, list.pageCount)
        val d = MacCms.parse(get(MacCms.detailUrl(api, listOf("3")))).list.single()
        assertEquals("示例影片03", d.name)
        assertEquals(listOf("m3u8", "mp4", "aes"), d.lines.map { it.name })
        assertEquals(3, d.lines[0].episodes.size)
        assertEquals("更新至3集", d.remarks)
    }

    @Test
    fun speedTestOrderingAndAutoHide(): Unit = runBlocking {
        need()
        val ids = listOf("dead", "slow", "good")
        val srcs = ids.mapIndexed { i, id -> Source("b:$id", id, "$base/$id/api.php/provide/vod", "json", true, order = i) }
        val health = mutableMapOf<String, HealthEntity>()
        repeat(3) { round ->
            val lat = coroutineScope { srcs.map { async { it.id to HealthPolicy.probeSource(it.api, 5000) } }.awaitAll() }.toMap()
            val allFailed = lat.values.none { it >= 0 }
            val offline = HealthPolicy.isOffline(allFailed, lat.size, networkValidated = true)
            for (s in srcs) {
                val old = health[s.id]
                if (old?.removed == true) continue
                val f = HealthPolicy.nextFailCount(old?.failCount ?: 0, lat[s.id]!! >= 0, offline)
                health[s.id] = HealthEntity(s.id, HealthEntity.KIND_SOURCE, s.name, s.api, "json", true, lat[s.id]!!, f, 1000L + round, HealthPolicy.shouldRemove(f))
            }
            val visible = buildSourceItems(srcs, health.values.toList()).map { it.source.name }
            if (round < 2) assertEquals("round $round", listOf("good", "slow", "dead"), visible)
            else assertEquals("dead hidden after 3 fails", listOf("good", "slow"), visible)
        }
        assertTrue(health["b:slow"]!!.latencyMs > health["b:good"]!!.latencyMs)
        assertEquals(3, health["b:dead"]!!.failCount)
        // 恢复
        health.remove("b:dead")
        assertEquals(listOf("good", "slow", "dead"), buildSourceItems(srcs, health.values.toList()).map { it.source.name })
    }

    @Test
    fun m3u8PlainAndResume(): Unit = runBlocking {
        need()
        val dir = tmpDir()
        val idx = M3u8Downloader(dir, 4) { _, _, _ -> }.download("$base/media/hls/index.m3u8")
        val segs = dir.listFiles()!!.filter { it.name.startsWith("seg_") }.sortedBy { it.name }
        assertEquals(6, segs.size)
        assertArrayEquals(raw("/media/hls/seg2.ts"), segs[2].readBytes())
        assertTrue(idx.readText().contains("seg_00005.ts"))
        assertTrue(!idx.readText().contains("http"))
        // 断点续传：删掉 2 个分片后重下，只请求缺失的
        segs[1].delete(); segs[4].delete()
        raw("/__reset")
        M3u8Downloader(dir, 4) { _, _, _ -> }.download("$base/media/hls/index.m3u8")
        val st = stats()
        assertEquals(1, st["/media/hls/seg1.ts"]); assertEquals(1, st["/media/hls/seg4.ts"])
        assertEquals(null, st["/media/hls/seg0.ts"])
        dir.deleteRecursively()
    }

    @Test
    fun m3u8MasterAndAes128(): Unit = runBlocking {
        need()
        val dir = tmpDir()
        var lastDone = 0; var lastTotal = 0
        val idx = M3u8Downloader(dir, 3) { d, t, _ -> lastDone = d; lastTotal = t }.download("$base/media/master.m3u8")
        assertEquals(6, lastTotal); assertEquals(6, lastDone)
        val text = idx.readText()
        // 选择了最高码率（加密那一路），key 改写为本地文件
        assertTrue(text, text.contains("METHOD=AES-128,URI=\"key_0.key\""))
        val key = File(dir, "key_0.key").readBytes()
        assertArrayEquals(raw("/media/hls_aes/enc.key"), key)
        val iv = Regex("IV=0x([0-9a-fA-F]{32})").find(text)!!.groupValues[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val plain = c.doFinal(File(dir, "seg_00000.ts").readBytes())
        assertEquals(0x47.toByte(), plain[0]) // MPEG-TS 同步字节
        assertEquals(0x47.toByte(), plain[188])
        dir.deleteRecursively()
    }

    @Test
    fun mp4RangeDownloadInterruptAndResume(): Unit = runBlocking {
        need()
        val src = raw("/media/sample.mp4")
        val chunk = 8L * 1024
        val chunks = ((src.size + chunk - 1) / chunk).toInt()
        val dir = tmpDir()
        // 第一次：下载到约 40% 时中断（模拟杀进程 / 暂停）
        var job: Job? = null
        job = launch(Dispatchers.IO) {
            Mp4Downloader(dir, 2, chunk) { b, t -> if (b > t * 0.4) job?.cancel(CancellationException("interrupt")) }
                .download("$base/media/sample.mp4")
        }
        job.join()
        val state = File(dir, "chunks.state")
        assertTrue("state should survive interrupt", state.exists())
        val doneBefore = state.readLines().filter { it.isNotBlank() }.toSet().size
        assertTrue(doneBefore in 1 until chunks)
        raw("/__reset")
        var finalBytes = 0L
        val out = Mp4Downloader(dir, 4, chunk) { b, _ -> finalBytes = b }.download("$base/media/sample.mp4")
        assertArrayEquals(src, out.readBytes())
        assertEquals(src.size.toLong(), finalBytes)
        val ranged = stats()["range:/media/sample.mp4"] ?: 0
        // 1 次探测 + 只下载剩余分块
        assertEquals(1 + (chunks - doneBefore), ranged)
        assertTrue(!state.exists())
        dir.deleteRecursively()
    }

    @Test
    fun mp4WithoutRangeSupportFallsBack(): Unit = runBlocking {
        need()
        ctl("ranges=off")
        try {
            val dir = tmpDir()
            val out = Mp4Downloader(dir, 4, 8L * 1024) { _, _ -> }.download("$base/media/sample.mp4")
            assertArrayEquals(raw("/media/sample.mp4"), out.readBytes())
            dir.deleteRecursively()
        } finally { ctl("ranges=on") }
    }

    @Test
    fun otaUpdateJsonAndMd5(): Unit = runBlocking {
        need()
        val info = UpdateLogic.parse(get("$base/update.json?vc=205&t=1"))
        assertTrue(UpdateLogic.isNewer(info, 107))
        val f = File.createTempFile("ota", ".apk")
        Http.client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r -> f.writeBytes(r.body!!.bytes()) }
        assertTrue(UpdateLogic.md5Matches(f, info.md5))
        f.appendBytes(byteArrayOf(1))
        assertTrue(!UpdateLogic.md5Matches(f, info.md5))
        f.delete()
    }

    // ---------- v1.1 ----------

    @Test
    fun parseApiWithRefererHeaderAndNestedJsonPath(): Unit = runBlocking {
        need()
        val target = "$base/parse2/?url=" + java.net.URLEncoder.encode("https://page.example/v/1.html", "UTF-8")
        // 不带 Referer：403
        val code = (URL(target).openConnection() as HttpURLConnection).responseCode
        assertEquals(403, code)
        val body = Http.client.getString(target, mapOf("Referer" to "https://ref.example/"))
        // 默认字段列表里的 data.playurl 能取到
        val url = com.vplayer.app.player.JsonPick.pick(body)
        assertEquals("$base/media/hls_ad/index.m3u8", url)
        assertEquals(url, com.vplayer.app.player.JsonPick.pick(body, "data.playurl"))
    }

    @Test
    fun m3u8DownloadSkipsAdSegments(): Unit = runBlocking {
        need()
        val dir = tmpDir()
        raw("/__reset")
        val d = M3u8Downloader(dir, 3, adRule = com.vplayer.app.player.ad.AdRule()) { _, _, _ -> }
        val idx = d.download("$base/media/hls_ad/index.m3u8")
        val segs = dir.listFiles()!!.filter { it.name.startsWith("seg_") }.sortedBy { it.name }
        assertEquals(6, segs.size)
        assertEquals(1, d.adResult?.removedSegments)
        assertArrayEquals(raw("/media/hls/seg3.ts"), segs[3].readBytes())
        val st = stats()
        assertEquals(null, st["/media/ad/adv_x91.ts"])   // 广告分片根本没下载
        assertEquals(1, Regex("#EXT-X-DISCONTINUITY").findAll(idx.readText()).count())
        // 不开去广告：7 个分片
        val dir2 = tmpDir()
        M3u8Downloader(dir2, 3) { _, _, _ -> }.download("$base/media/hls_ad/index.m3u8")
        assertEquals(7, dir2.listFiles()!!.count { it.name.startsWith("seg_") })
        dir.deleteRecursively(); dir2.deleteRecursively()
    }
}

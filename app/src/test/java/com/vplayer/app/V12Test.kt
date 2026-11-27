package com.vplayer.app

import com.vplayer.app.data.config.ConfigLoader
import com.vplayer.app.data.config.ConfigLoader.builtInParses
import com.vplayer.app.data.config.ConfigLoader.builtInSources
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.net.Warmup
import com.vplayer.app.data.prefs.AppSettings
import com.vplayer.app.player.ParseOrder
import com.vplayer.app.player.ParseRace
import com.vplayer.app.player.ad.AdRule
import com.vplayer.app.player.engine.HlsLocalizer
import com.vplayer.app.player.engine.Kernel
import com.vplayer.app.player.engine.KernelPolicy
import com.vplayer.app.player.engine.PlaybackProfiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** v1.2：播放源专属解析（含内联写法）/ 解析竞速 / 内核选择与回退 / 加载预设 / IJK 去广告改写 / 连接预热 */
class V12Test {

    // ---------- A. 解析接口挂在播放源下 ----------

    private val v11Config = """
        {"configVersion":3,
         "sources":[{"key":"s1","name":"源A","api":"https://a.example/api.php/provide/vod","parses":["p2"],"lineParses":{"m3u8":["p1"]}}],
         "parses":[{"key":"p1","name":"解析1","url":"https://p1.example/?url="},{"key":"p2","name":"解析2","url":"https://p2.example/?url=","type":"webview"}],
         "player":{"bufferSeconds":40}}
    """.trimIndent()

    @Test
    fun v11ConfigStillLoadsUnchanged() {
        val c = ConfigLoader.parse(v11Config)
        val s = c.builtInSources().single()
        assertEquals("b:s1", s.id)
        assertEquals(listOf("p2"), s.parseKeys)
        assertEquals(mapOf("m3u8" to listOf("p1")), s.lineParses)
        assertEquals("", s.kernel)
        val ps = c.builtInParses()
        assertEquals(listOf("p1", "p2"), ps.map { it.key })
        assertTrue(ps.all { it.ownerId.isEmpty() })      // v1.1 的解析都是通用解析
        assertEquals("auto", c.player.kernel)            // 新字段有默认值
        assertEquals("balanced", c.player.preset)
        assertEquals(40, c.player.bufferSeconds)
    }

    @Test
    fun inlineParseObjectsAreHoistedAsSourceOwned() {
        val text = """
            {"sources":[
               {"key":"s1","api":"https://a.example/api","kernel":"IJK",
                "parses":[{"name":"专属A","url":" https://pa.example/?url= ","headers":{"Referer":"https://a.example/"},"timeout":5}, "g1"],
                "lineParses":{"m3u8":[{"url":"https://pl.example/?u="}]}},
               {"api":"https://b.example/api","parses":[{"url":"https://pb.example/?url="}]}],
             "parses":[{"key":"g1","url":"https://g1.example/?url="}]}
        """.trimIndent()
        val c = ConfigLoader.parse(text)
        val src = c.builtInSources()
        val a = src.first { it.api.contains("a.example") }
        val b = src.first { it.api.contains("b.example") }
        assertEquals("ijk", a.kernel)
        assertEquals(listOf("s1_p1", "g1"), a.parseKeys)
        assertEquals(listOf("s1_p2"), a.lineParses["m3u8"])
        assertEquals("b:s2", b.id)                         // 没写 key 的源用位置生成 key，专属解析能对上
        assertEquals(listOf("s2_p1"), b.parseKeys)
        val ps = c.builtInParses().associateBy { it.key }
        assertEquals("b:s1", ps["s1_p1"]!!.ownerId)
        assertEquals("https://pa.example/?url=", ps["s1_p1"]!!.url)
        assertEquals("https://a.example/", ps["s1_p1"]!!.headers["Referer"])
        assertEquals(5, ps["s1_p1"]!!.timeoutSec)
        assertEquals("b:s1", ps["s1_p2"]!!.ownerId)
        assertEquals("b:s2", ps["s2_p1"]!!.ownerId)
        assertEquals("", ps["g1"]!!.ownerId)
        assertEquals(4, ps.size)
    }

    @Test
    fun inlineParseKeyCollisionGetsNewKeyAndSameKeySameUrlIsReused() {
        val text = """
            {"sources":[{"key":"s1","api":"https://a.example/api",
               "parses":[{"key":"g1","url":"https://g1.example/?url="},{"key":"g1","url":"https://other.example/?url="}]}],
             "parses":[{"key":"g1","url":"https://g1.example/?url="}]}
        """.trimIndent()
        val c = ConfigLoader.parse(text)
        val s = c.builtInSources().single()
        assertEquals(listOf("g1", "s1_p1"), s.parseKeys)
        val ps = c.builtInParses().associateBy { it.key }
        assertEquals("", ps["g1"]!!.ownerId)                 // 引用已有的通用解析，没有复制
        assertEquals("https://other.example/?url=", ps["s1_p1"]!!.url)
        assertEquals(2, ps.size)
    }

    @Test
    fun repoConfigJsonHasNoBuiltInContent() {
        val f = listOf(File("../config.json"), File("config.json")).first { it.exists() }
        val c = ConfigLoader.parse(f.readText())
        assertTrue(c.sources.isEmpty())
        assertTrue(c.parses.isEmpty())
    }

    private fun p(key: String, owner: String = "") = ParseApi("b:$key", key, "https://$key.example/?url=", "json", true, key = key, ownerId = owner)

    @Test
    fun parseOrderKeepsOwnedParsesToTheirSource() {
        val all = listOf(p("g1"), p("a1", "b:A"), p("a2", "b:A"), p("aLine", "b:A"), p("b1", "b:B"), p("g2"))
        val a = Source("b:A", "A", "https://a/api", "json", true, parseKeys = listOf("a2"), lineParses = mapOf("m3u8" to listOf("aLine")))
        val b = Source("b:B", "B", "https://b/api", "json", true)
        // 线路命中：线路绑定 → 源绑定 → 其余专属 → 通用
        assertEquals(listOf("aLine", "a2", "a1", "g1", "g2"), ParseOrder.forSource(all, a, "m3u8").map { it.key })
        // 其他线路：限定在 m3u8 线路的专属解析不出现
        assertEquals(listOf("a2", "a1", "g1", "g2"), ParseOrder.forSource(all, a, "mp4").map { it.key })
        // 别的源看不到 A 的专属解析
        assertEquals(listOf("b1", "g1", "g2"), ParseOrder.forSource(all, b, null).map { it.key })
        // 不知道是哪个源：只有通用解析
        assertEquals(listOf("g1", "g2"), ParseOrder.forSource(all, null, null).map { it.key })
        // 本地源（没有专属解析）：只有通用
        val local = Source("l:1", "L", "https://l/api", "json", false)
        assertEquals(listOf("g1", "g2"), ParseOrder.forSource(all, local, null).map { it.key })
    }

    // ---------- D. 解析竞速 ----------

    @Test
    fun parseRaceTakesFirstValidAndSlidesWindow(): Unit = runBlocking {
        val started = mutableListOf<String>()
        val delays = mapOf("slow" to 600L, "fast" to 50L, "bad" to 10L, "late" to 30L)
        val results = mapOf("slow" to "u-slow", "fast" to "u-fast", "bad" to null, "late" to "u-late")
        val t0 = System.currentTimeMillis()
        val r = ParseRace.first(listOf("slow", "fast", "late"), 2) { k -> synchronized(started) { started += k }; delay(delays[k]!!); results[k] }
        assertEquals("fast" to "u-fast", r)
        assertTrue("竞速应比等慢的快", System.currentTimeMillis() - t0 < 500)
        assertFalse("赢了以后不再启动后面的候选", "late" in started)
        // 失败的位置立即由下一个补上
        val r2 = ParseRace.first(listOf("bad", "slow", "late"), 2) { k -> delay(delays[k]!!); results[k] }
        assertEquals("late" to "u-late", r2)
        // 抛异常的当失败处理；全部失败返回 null
        assertNull(ParseRace.first(listOf("x", "y", "z"), 2) { k -> if (k == "x") throw IllegalStateException("boom") else null })
        // width = 1：严格按顺序
        val order = mutableListOf<String>()
        val r3 = ParseRace.first(listOf("bad", "slow", "fast"), 1) { k -> order += k; delay(delays[k]!!); results[k] }
        assertEquals("slow" to "u-slow", r3)
        assertEquals(listOf("bad", "slow"), order)
        assertNull(ParseRace.first(emptyList<String>(), 2) { "x" })
    }

    // ---------- C. 内核 ----------

    @Test
    fun kernelChoice() {
        assertEquals(Kernel.EXO, KernelPolicy.initial(Kernel.AUTO, "", true))
        assertEquals(Kernel.IJK, KernelPolicy.initial(Kernel.AUTO, "ijk", true))
        assertEquals(Kernel.SYSTEM, KernelPolicy.initial(Kernel.AUTO, "ijk", false)) // IJK 库加载不了
        assertEquals(Kernel.EXO, KernelPolicy.initial(Kernel.EXO, "ijk", true))      // 用户手选优先于源配置
        assertEquals(Kernel.SYSTEM, KernelPolicy.initial(Kernel.SYSTEM, "", true))
        assertEquals(Kernel.EXO, KernelPolicy.initial(Kernel.AUTO, "nonsense", true))
        assertEquals(Kernel.IJK, Kernel.of(" IJK "))
        assertEquals(Kernel.AUTO, Kernel.of(null))
    }

    @Test
    fun kernelFallbackOnlyInAutoAndOnlyForFormatErrors() {
        val decoderUnsupported = 4005
        val containerUnsupported = 3003
        val network = 2001
        assertEquals(Kernel.IJK, KernelPolicy.fallback(Kernel.AUTO, Kernel.EXO, decoderUnsupported, emptySet(), true))
        assertEquals(Kernel.IJK, KernelPolicy.fallback(Kernel.AUTO, Kernel.EXO, containerUnsupported, emptySet(), true))
        assertEquals(Kernel.SYSTEM, KernelPolicy.fallback(Kernel.AUTO, Kernel.IJK, decoderUnsupported, setOf(Kernel.EXO), true))
        assertNull(KernelPolicy.fallback(Kernel.AUTO, Kernel.SYSTEM, decoderUnsupported, setOf(Kernel.EXO, Kernel.IJK), true))
        assertEquals(Kernel.SYSTEM, KernelPolicy.fallback(Kernel.AUTO, Kernel.EXO, decoderUnsupported, emptySet(), false))
        // 源首选 IJK 失败 → 回到 Exo
        assertEquals(Kernel.EXO, KernelPolicy.fallback(Kernel.AUTO, Kernel.IJK, decoderUnsupported, emptySet(), true))
        // 网络错误：不换内核（换解析 / 换线路）
        assertNull(KernelPolicy.fallback(Kernel.AUTO, Kernel.EXO, network, emptySet(), true))
        // 用户手选了内核：不自动换
        assertNull(KernelPolicy.fallback(Kernel.EXO, Kernel.EXO, decoderUnsupported, emptySet(), true))
        // IJK / 系统播放器错误码映射
        assertEquals(4005, KernelPolicy.nativeErrorCode(1, -1010))
        assertEquals(3001, KernelPolicy.nativeErrorCode(1, -1007))
        assertEquals(2002, KernelPolicy.nativeErrorCode(-110, 0))
        assertEquals(2000, KernelPolicy.nativeErrorCode(-10000, 0))
        assertTrue(KernelPolicy.isFormatError(KernelPolicy.nativeErrorCode(1, -1010)))
        assertFalse(KernelPolicy.isFormatError(KernelPolicy.nativeErrorCode(-10000, 0)))
    }

    @Test
    fun playbackPresets() {
        val bal = PlaybackProfiles.of(AppSettings(preset = "balanced"))
        // 均衡 = v1.1 的参数（15s / 30s / 1s / 2s / 512MB）
        assertEquals(listOf(15_000, 30_000, 1_000, 2_000, 512), listOf(bal.minBufferMs, bal.maxBufferMs, bal.startMs, bal.rebufferMs, bal.cacheMb))
        val fast = PlaybackProfiles.of(AppSettings(preset = "fast"))
        val smooth = PlaybackProfiles.of(AppSettings(preset = "smooth"))
        assertTrue(fast.startMs < bal.startMs && bal.startMs < smooth.startMs)
        assertTrue(fast.maxBufferMs <= bal.maxBufferMs && bal.maxBufferMs < smooth.maxBufferMs)
        assertTrue(fast.connectTimeoutSec < smooth.connectTimeoutSec)
        assertEquals("balanced", PlaybackProfiles.of(AppSettings(preset = "???")).id)
        // 自定义：来自设置，并满足 ExoPlayer 的约束 start/rebuffer ≤ min ≤ max
        val custom = PlaybackProfiles.of(
            AppSettings(preset = "custom", minBufferSeconds = 50, bufferSeconds = 20, startMs = 9000, rebufferMs = 30000, cacheSizeMb = 10,
                preloadNext = false, connectTimeoutSec = 1, readTimeoutSec = 500, dnsPrefetch = false)
        )
        assertEquals(20_000, custom.maxBufferMs)
        assertEquals(20_000, custom.minBufferMs)
        assertTrue(custom.startMs <= custom.minBufferMs && custom.rebufferMs <= custom.minBufferMs)
        assertEquals(64, custom.cacheMb)
        assertEquals(2, custom.connectTimeoutSec)
        assertEquals(120, custom.readTimeoutSec)
        assertFalse(custom.preloadNext)
        assertFalse(custom.dnsPrefetch)
        assertTrue(bal.summary().contains("起播 1 秒"))
        assertTrue(fast.summary().contains("起播 0.5 秒"))
    }

    // ---------- IJK 去广告：本地改写播放列表 ----------

    private val media = "https://cdn.example/vod/abc/index.m3u8"
    private fun seg(d: Double, u: String) = "#EXTINF:$d,\n$u\n"

    @Test
    fun hlsAbsolutizeAndVariant() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x01\n#EXT-X-MAP:URI=\"../init.mp4\"\n#EXTINF:4,\nseg0.ts\n#EXTINF:4,\n/abs/seg1.ts\n#EXTINF:4,\nhttps://other.example/seg2.ts\n"
        val out = HlsLocalizer.absolutize(text, media)
        assertTrue(out.contains("URI=\"https://cdn.example/vod/abc/key.bin\""))
        assertTrue(out.contains("URI=\"https://cdn.example/vod/init.mp4\""))
        assertTrue(out.contains("\nhttps://cdn.example/vod/abc/seg0.ts\n"))
        assertTrue(out.contains("\nhttps://cdn.example/abs/seg1.ts\n"))
        assertTrue(out.contains("\nhttps://other.example/seg2.ts\n"))
        assertTrue(out.contains("IV=0x01"))
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhd/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200000\nsd/index.m3u8\n"
        assertEquals("https://cdn.example/vod/abc/hd/index.m3u8", HlsLocalizer.firstVariant(master, media))
        assertNull(HlsLocalizer.firstVariant(text, media))
    }

    @Test
    fun hlsRewriteRemovesAdsWithAbsoluteUris() {
        val main1 = (0 until 30).joinToString("") { seg(4.0, "seg%03d.ts".format(it)) }
        val ad = (0 until 3).joinToString("") { seg(5.0, "https://ads.example/x/ad$it.ts") }
        val main2 = (30 until 60).joinToString("") { seg(4.0, "seg%03d.ts".format(it)) }
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + main1 + "#EXT-X-DISCONTINUITY\n" + ad + "#EXT-X-DISCONTINUITY\n" + main2 + "#EXT-X-ENDLIST\n"
        val r = HlsLocalizer.rewrite(text, media, AdRule())
        assertNotNull(r)
        assertEquals(3, r!!.removedSegments)
        val uris = r.text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(60, uris.size)
        assertTrue(uris.all { it.startsWith("https://cdn.example/vod/abc/seg") })
        // 直播（没有 ENDLIST）和没有广告的：不改写
        assertNull(HlsLocalizer.rewrite(text.replace("#EXT-X-ENDLIST\n", ""), media, AdRule()))
        assertNull(HlsLocalizer.rewrite("#EXTM3U\n" + main1 + "#EXT-X-ENDLIST\n", media, AdRule()))
    }

    // ---------- D. 连接预热 ----------

    @Test
    fun warmupOriginsDeduped() {
        val o = Warmup.origins(listOf("https://a.example/api.php/provide/vod", "https://a.example/other?x=1", "http://b.example:8080/p?url=", "not a url", ""))
        assertEquals(listOf("https://a.example:443/", "http://b.example:8080/"), o)
    }

    @Test
    fun racesCountStartedCandidates(): Unit = runBlocking {
        val n = AtomicInteger()
        ParseRace.first((1..10).toList(), 2) { k -> n.incrementAndGet(); delay(20); if (k == 5) "ok" else null }
        assertEquals(6, n.get()) // 1..5 依次失败补位，第 5 个成功时第 6 个已经在跑
    }
}

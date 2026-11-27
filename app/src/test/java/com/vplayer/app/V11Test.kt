package com.vplayer.app

import com.vplayer.app.data.config.AdFilterCfg
import com.vplayer.app.data.config.AppConfig
import com.vplayer.app.data.config.ConfigLoader.builtInParses
import com.vplayer.app.data.config.ConfigLoader.builtInSources
import com.vplayer.app.data.model.Episode
import com.vplayer.app.data.model.ParseApi
import com.vplayer.app.data.model.PlayLine
import com.vplayer.app.data.model.Source
import com.vplayer.app.player.AdRules
import com.vplayer.app.player.Failover
import com.vplayer.app.player.HoldSeek
import com.vplayer.app.player.JsonPick
import com.vplayer.app.player.ParseOrder
import com.vplayer.app.player.TapZone
import com.vplayer.app.player.ad.AdFilter
import com.vplayer.app.player.ad.AdRule
import com.vplayer.app.update.UpdateLogic
import com.vplayer.app.util.CrashLog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** v1.1 新功能：去广告 / 解析绑定 / JSON 字段 / ABI 选包 / 手势逻辑 / 自动换线 / 崩溃日志 */
class V11Test {

    // ---------- 合成的 M3U8 ----------
    private val base = "https://cdn.example/vod/20260101/abc/index.m3u8"

    private fun seg(d: Double, uri: String) = "#EXTINF:$d,\n$uri\n"
    private fun content(from: Int, n: Int, d: Double = 4.0) = (from until from + n).joinToString("") { seg(d, "seg%04d.ts".format(it)) }
    private fun playlist(body: String, endlist: Boolean = true) =
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n" + body + if (endlist) "#EXT-X-ENDLIST\n" else ""

    private fun uris(text: String) = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun adAtStartFromOtherHostIsRemoved() {
        val ads = seg(5.0, "https://ads.example/x/ad1.ts") + seg(5.0, "https://ads.example/x/ad2.ts") + seg(5.0, "https://ads.example/x/ad3.ts")
        val text = playlist(ads + "#EXT-X-DISCONTINUITY\n" + content(0, 60))
        val r = AdFilter.filter(text, base, AdRule())
        assertEquals(3, r.removedSegments)
        assertEquals(15.0, r.removedSeconds, 0.001)
        assertEquals(60, uris(r.text).size)
        assertTrue(uris(r.text).all { it.startsWith("seg") })
        // 广告在最前面：删掉后不应留下开头的 DISCONTINUITY
        assertFalse(r.text.substringBefore("seg0000").contains("#EXT-X-DISCONTINUITY"))
        assertTrue(r.text.trim().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun adInMiddleWithDifferentNamingIsRemovedAndOneDiscontinuityKept() {
        val ad = (0 until 4).joinToString("") { seg(3.0, "../../adv/2026/0f$it.ts") } // 同域名、不同目录、不同文件名
        val text = playlist(content(0, 40) + "#EXT-X-DISCONTINUITY\n" + ad + "#EXT-X-DISCONTINUITY\n" + content(40, 40))
        val r = AdFilter.filter(text, base, AdRule())
        assertEquals(4, r.removedSegments)
        assertEquals(80, uris(r.text).size)
        assertEquals(1, r.text.lines().count { it == "#EXT-X-DISCONTINUITY" }) // 两段正片之间时间戳不连续，保留一个
        assertTrue(r.text.indexOf("seg0039.ts") < r.text.indexOf("#EXT-X-DISCONTINUITY"))
        assertTrue(r.text.indexOf("#EXT-X-DISCONTINUITY") < r.text.indexOf("seg0040.ts"))
    }

    @Test
    fun legitMultiDiscontinuityStreamStaysIntact() {
        // 正片被切成几段（同目录、同命名、同时长），中间有很短的一段：不能删
        val text = playlist(
            content(0, 30) + "#EXT-X-DISCONTINUITY\n" + content(30, 3) + "#EXT-X-DISCONTINUITY\n" +
                content(33, 30) + "#EXT-X-DISCONTINUITY\n" + content(63, 25)
        )
        val r = AdFilter.filter(text, base, AdRule())
        assertFalse(r.changed)
        assertEquals(text, r.text)
    }

    @Test
    fun durationDifferenceAloneNeverRemoves() {
        val shortSameNaming = (100 until 103).joinToString("") { seg(1.5, "seg%04d.ts".format(it)) }
        val text = playlist(content(0, 50) + "#EXT-X-DISCONTINUITY\n" + shortSameNaming + "#EXT-X-DISCONTINUITY\n" + content(50, 50))
        assertFalse(AdFilter.filter(text, base, AdRule()).changed)
    }

    @Test
    fun safetyGuardsNeverRemoveMoreThan20PercentOrMainRun() {
        // “广告”段 25 秒，正片只有 40 秒：超过 20% → 什么都不删
        val ad = (0 until 5).joinToString("") { seg(5.0, "https://ads.example/a$it.ts") }
        val text = playlist(ad + "#EXT-X-DISCONTINUITY\n" + content(0, 10))
        assertFalse(AdFilter.filter(text, base, AdRule()).changed)
        // 只有一段（无 DISCONTINUITY）：不删
        assertFalse(AdFilter.filter(playlist(content(0, 20)), base, AdRule(keywords = listOf("seg"))).changed)
        // 超过 maxAdSeconds 的段不算广告
        val longAd = (0 until 10).joinToString("") { seg(4.0, "https://ads.example/b$it.ts") }
        val t2 = playlist(content(0, 100) + "#EXT-X-DISCONTINUITY\n" + longAd + "#EXT-X-DISCONTINUITY\n" + content(100, 100))
        assertFalse(AdFilter.filter(t2, base, AdRule(maxAdSeconds = 30.0)).changed)
        assertTrue(AdFilter.filter(t2, base, AdRule(maxAdSeconds = 60.0)).changed)
    }

    @Test
    fun liveAndMasterPlaylistsUntouched() {
        val ad = seg(5.0, "https://ads.example/a.ts")
        val live = playlist(ad + "#EXT-X-DISCONTINUITY\n" + content(0, 30), endlist = false)
        assertFalse(AdFilter.filter(live, base, AdRule()).changed)
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
        assertEquals(master, AdFilter.filter(master, base, AdRule()).text)
    }

    @Test
    fun keywordAndRegexRulesAndKeyTagsPreserved() {
        // 同目录同命名（启发式不会删），但地址里带关键词 → 按源规则删除
        val ad = "#EXT-X-KEY:METHOD=NONE\n" + seg(4.0, "seg9000.ts?type=adslot") + seg(4.0, "seg9001.ts?type=adslot")
        val text = playlist(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"k1.key\"\n" + content(0, 40) + "#EXT-X-DISCONTINUITY\n" + ad +
                "#EXT-X-DISCONTINUITY\n#EXT-X-KEY:METHOD=AES-128,URI=\"k1.key\"\n" + content(40, 40)
        )
        assertFalse(AdFilter.filter(text, base, AdRule()).changed)
        val r = AdFilter.filter(text, base, AdRule(keywords = listOf("AdSlot")))
        assertEquals(2, r.removedSegments)
        // 状态标签原样保留：KEY 的数量不变
        assertEquals(3, r.text.lines().count { it.startsWith("#EXT-X-KEY") })
        val r2 = AdFilter.filter(text, base, AdRule(smart = false, regex = "seg90\\d\\d\\.ts"))
        assertEquals(2, r2.removedSegments)
        // 非法正则不崩溃
        assertFalse(AdFilter.filter(text, base, AdRule(regex = "([")).changed)
    }

    @Test
    fun adRulesMergeGlobalSwitchAndSourceConfig() {
        assertNull(AdRules.effective(null, globalOn = false))
        assertEquals(AdRule(), AdRules.effective(null, globalOn = true))
        assertNull(AdRules.effective(AdFilterCfg(enabled = false), globalOn = true))
        val r = AdRules.effective(AdFilterCfg(enabled = true, keywords = listOf(" ad ", ""), discontinuityMax = 45), true)!!
        assertEquals(listOf("ad"), r.keywords)
        assertEquals(45.0, r.maxAdSeconds, 0.0)
    }

    // ---------- 解析绑定 ----------
    private fun p(key: String) = ParseApi("b:$key", key.uppercase(), "https://$key.example/?url=", "json", true, key = key)

    @Test
    fun boundParsesComeFirstThenGlobal() {
        val all = listOf(p("a"), p("b"), p("c"), p("d")) // 已按测速排序
        val src = Source("b:s", "S", "https://s.example/api", "json", true, parseKeys = listOf("c", "gone"),
            lineParses = mapOf("M3U8线路" to listOf("d")))
        assertEquals(listOf("a", "b", "c", "d"), ParseOrder.forSource(all, null, null).map { it.key })
        assertEquals(listOf("c", "a", "b", "d"), ParseOrder.forSource(all, src, "其他线路").map { it.key })
        assertEquals(listOf("d", "c", "a", "b"), ParseOrder.forSource(all, src, "M3U8线路").map { it.key })
        assertEquals(listOf("d", "c", "a", "b"), ParseOrder.forSource(all, src, "m3u8线路 ").map { it.key }) // 大小写 / 空格不敏感
    }

    @Test
    fun configSchemaV11DecodesAndOldConfigStillWorks() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val cfg = json.decodeFromString(
            AppConfig.serializer(), """
            {"sources":[{"key":"s1","name":"S","api":"https://s.example/api.php/provide/vod","parses":["p1"],
               "lineParses":{"lineA":["p2","p1"]},"adFilter":{"enabled":true,"keywords":["adv"],"regex":"","discontinuityMax":20}},
              {"key":"s2","api":"https://t.example/api.php/provide/vod"}],
             "parses":[{"key":"p1","url":"https://p1.example/?url=","headers":{"Referer":"https://r.example/"},"timeout":5,"jsonPath":"data.playurl"},
              {"key":"p2","url":"https://p2.example/?url="}]}
            """.trimIndent()
        )
        val s = cfg.builtInSources()
        assertEquals(listOf("p1"), s[0].parseKeys)
        assertEquals(listOf("p2", "p1"), s[0].lineParses["lineA"])
        assertEquals(20, s[0].adFilter!!.discontinuityMax)
        assertNull(s[1].adFilter)
        val ps = cfg.builtInParses()
        assertEquals("p1", ps[0].key)
        assertEquals("https://r.example/", ps[0].headers["Referer"])
        assertEquals(5, ps[0].timeoutSec)
        assertEquals("data.playurl", ps[0].jsonPath)
        // 仓库自带的 config.json（空源）仍然能读
        val repo = java.io.File("../config.json").takeIf { it.exists() } ?: java.io.File("config.json")
        if (repo.exists()) {
            val c = json.decodeFromString(AppConfig.serializer(), repo.readText())
            assertTrue(c.sources.isEmpty() && c.parses.isEmpty())
        }
    }

    @Test
    fun jsonFieldPathPicking() {
        assertEquals("https://v.example/a.m3u8", JsonPick.pick("""{"code":200,"url":"https://v.example/a.m3u8"}"""))
        assertEquals("https://v.example/b.m3u8", JsonPick.pick("""{"data":{"url":"https://v.example/b.m3u8"}}"""))
        assertEquals("https://v.example/c.m3u8", JsonPick.pick("""{"data":{"playurl":"https://v.example/c.m3u8"}}"""))
        assertEquals("https://v.example/d.m3u8", JsonPick.pick("""{"data":"https://v.example/d.m3u8"}"""))
        assertEquals("https://v.example/e.m3u8", JsonPick.pick("""{"result":{"url":"https://v.example/e.m3u8"}}"""))
        assertEquals("https://v.example/f.m3u8", JsonPick.pick("""{"m3u8":"https://v.example/f.m3u8"}"""))
        // 自定义路径（含数组下标），协议相对地址补 https
        assertEquals("https://v.example/g.m3u8", JsonPick.pick("""{"res":{"list":[{"src":"//v.example/g.m3u8"}]}}""", "res.list.0.src"))
        // 默认路径都不命中 / url 不是地址 → null
        assertNull(JsonPick.pick("""{"code":404,"url":"","msg":"失败"}"""))
        assertNull(JsonPick.pick("<html>not json</html>"))
        // 纯文本直接返回地址
        assertEquals("https://v.example/h.mp4", JsonPick.pick("https://v.example/h.mp4\n"))
    }

    // ---------- ABI 选包 ----------
    @Test
    fun updatePicksMatchingAbiAndFallsBackToUniversal() {
        val info = UpdateLogic.parse(
            """{"versionCode":120,"versionName":"1.1.0","apkUrl":"https://x.example/u.apk","md5":"uu","size":"3.1 MB",
               "apks":{"arm64-v8a":{"url":"https://x.example/a64.apk","md5":"a6","size":"3.0 MB"},
                       "armeabi-v7a":{"url":"https://x.example/v7.apk","md5":"v7","size":"3.0 MB"},
                       "universal":{"url":"https://x.example/u.apk","md5":"uu","size":"3.1 MB"}}}"""
        )
        assertEquals("a6", UpdateLogic.pickApk(info, listOf("arm64-v8a", "armeabi-v7a", "armeabi")).md5)
        assertEquals("v7", UpdateLogic.pickApk(info, listOf("armeabi-v7a", "armeabi")).md5)
        val x86 = UpdateLogic.pickApk(info, listOf("x86"))
        assertEquals("universal", x86.abi); assertEquals("https://x.example/u.apk", x86.url)
        // 旧格式 update.json（没有 apks）
        val old = UpdateLogic.parse("""{"versionCode":110,"apkUrl":"https://x.example/old.apk","md5":"oo"}""")
        assertEquals("https://x.example/old.apk", UpdateLogic.pickApk(old, listOf("arm64-v8a")).url)
        assertTrue(UpdateLogic.isNewer(old, 100))
        // 只有 apks、顶层没地址也算新版本
        val onlyMap = UpdateLogic.parse("""{"versionCode":130,"apks":{"universal":{"url":"https://x.example/u2.apk"}}}""")
        assertTrue(UpdateLogic.isNewer(onlyMap, 120))
        assertEquals("https://x.example/u2.apk", UpdateLogic.pickApk(onlyMap, listOf("arm64-v8a")).url)
    }

    // ---------- 手势 ----------
    @Test
    fun gestureZonesAndRewindSteps() {
        assertEquals(TapZone.LEFT, TapZone.of(100f, 1000f))
        assertEquals(TapZone.CENTER, TapZone.of(500f, 1000f))
        assertEquals(TapZone.RIGHT, TapZone.of(900f, 1000f))
        assertTrue(HoldSeek.isRewind(499f, 1000f)); assertFalse(HoldSeek.isRewind(501f, 1000f))
        assertEquals(1500L, HoldSeek.rewindStepMs(3f))
        assertEquals(1000L, HoldSeek.rewindStepMs(2f))
        assertEquals(0L, HoldSeek.nextRewindPos(800, 3f))
        assertEquals(8500L, HoldSeek.nextRewindPos(10_000, 3f))
        // 3 倍速下每 200ms 退 1.5 秒 ≈ 7.5 倍速倒退
        assertEquals("◀◀ 快退 3x", HoldSeek.label(true, 3f))
        assertEquals("快进 3x ▶▶", HoldSeek.label(false, 3f))
        assertEquals("快进 2.5x ▶▶", HoldSeek.label(false, 2.5f))
    }

    // ---------- 自动换线 ----------
    @Test
    fun failoverPicksNextUntriedLineKeepingEpisode() {
        fun line(name: String, vararg eps: String) = PlayLine(name, eps.map { Episode(it, "https://$name.example/$it.m3u8") })
        val lines = listOf(line("A", "第1集", "第2集", "第3集"), line("B", "第1集"), line("C", "第1集", "第2集", "第3集"), line("D"))
        assertEquals(2 to 2, Failover.nextLine(lines, 0, 2, setOf(0)))          // B 没有第 3 集 → C
        assertEquals(1 to 0, Failover.nextLine(lines, 0, 0, setOf(0)))
        assertEquals(0 to 1, Failover.nextLine(lines, 2, 1, setOf(2)))          // 绕回 A
        assertNull(Failover.nextLine(lines, 2, 2, setOf(0, 2)))                // 都试过了
        assertNull(Failover.nextLine(listOf(lines[0]), 0, 0, setOf(0)))        // 只有一条线路
        // 集名不同序：按名字对齐
        val swapped = listOf(line("A", "第1集", "第2集"), line("B", "第2集", "第1集"))
        assertEquals(1 to 0, Failover.nextLine(swapped, 0, 1, setOf(0)))
    }

    // ---------- 崩溃日志 ----------
    @Test
    fun crashLogKeepsLastTen() {
        val dir = Files.createTempDirectory("crash").toFile()
        repeat(13) { i -> java.io.File(dir, "crash-20260101-0000%02d-000.txt".format(i)).writeText("x$i") }
        java.io.File(dir, "app.log").writeText("keep")
        CrashLog.prune(dir, CrashLog.KEEP)
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(11, left.size)
        assertTrue("app.log" in left)
        assertFalse("crash-20260101-000000-000.txt" in left)
        assertTrue("crash-20260101-000012-000.txt" in left)
        val f = CrashLog.write(dir, Thread.currentThread(), IllegalStateException("boom"))
        assertTrue(f.readText().contains("IllegalStateException: boom"))
        assertEquals(10, dir.listFiles { x -> x.name.startsWith("crash-") }!!.size)
    }
}

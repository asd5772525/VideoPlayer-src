package com.vplayer.app

import com.vplayer.app.data.cms.HealthPolicy
import com.vplayer.app.data.cms.MacCms
import com.vplayer.app.data.cms.buildSourceItems
import com.vplayer.app.data.db.HealthEntity
import com.vplayer.app.data.model.PlayUrlParser
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.UpdateInfo
import com.vplayer.app.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 纯逻辑测试：不需要模拟服务器 */
class LogicTest {

    @Test
    fun playUrlEdgeCases() {
        // 多线路，线路名比地址段少；空线路跳过；集名缺失
        val d = "\$\$\$"
        val l = PlayUrlParser.parse(
            "a",
            "第1集\$http://x.example/1.m3u8#第2集\$http://x.example/2.m3u8#" + d + "" + d + "\$http://y.example/v.mp4",
        )
        assertEquals(2, l.size)
        assertEquals("a", l[0].name)
        assertEquals(2, l[0].episodes.size)
        assertEquals("线路3", l[1].name)
        assertEquals("正片", l[1].episodes[0].name)
        // 单集无名称 → 正片；末尾多余 #
        val s = PlayUrlParser.parse("m3u8", "http://z.example/a.m3u8#")
        assertEquals("正片", s[0].episodes[0].name)
        assertEquals(1, s[0].episodes.size)
        // 地址为空的集被过滤
        assertTrue(PlayUrlParser.parse("x", "第1集\$").isEmpty())
        assertTrue(PlayUrlParser.parse("x", "").isEmpty())
    }

    @Test
    fun xmlParsingWithKxml() {
        MacCms.xmlParserFactory = { org.kxml2.io.KXmlParser() }
        val xml = """<?xml version="1.0" encoding="utf-8"?><rss version="5.1"><list page="2" pagecount="5" pagesize="10" recordcount="44">
            <video><last>2026-01-01</last><id>7</id><tid>2</tid><name><![CDATA[示例]]></name><type>剧集</type><pic>http://p.example/a.jpg</pic>
            <note><![CDATA[更新至3集]]></note><dl><dd flag="m3u8"><![CDATA[第1集${'$'}http://a.example/1.m3u8#第2集${'$'}http://a.example/2.m3u8]]></dd>
            <dd flag="mp4"><![CDATA[正片${'$'}http://a.example/v.mp4]]></dd></dl><des><![CDATA[<p>简介</p>]]></des></video>
            </list><class><ty id="1">电影</ty><ty id="2">剧集</ty></class></rss>"""
        val p = MacCms.parse(xml)
        assertEquals(2, p.page)
        assertEquals(5, p.pageCount)
        assertEquals(1, p.list.size)
        val v = p.list[0]
        assertEquals("7", v.id); assertEquals("示例", v.name); assertEquals("简介", v.content)
        assertEquals(listOf("m3u8", "mp4"), v.lines.map { it.name })
        assertEquals(2, v.lines[0].episodes.size)
        assertEquals(listOf("电影", "剧集"), p.categories.map { it.name })
    }

    @Test
    fun threeConsecutiveFailuresHideThenRestore() {
        var fail = 0
        // 失败 2 次 → 成功清零
        fail = HealthPolicy.nextFailCount(fail, ok = false, offline = false)
        fail = HealthPolicy.nextFailCount(fail, ok = false, offline = false)
        assertEquals(2, fail)
        assertFalse(HealthPolicy.shouldRemove(fail))
        fail = HealthPolicy.nextFailCount(fail, ok = true, offline = false)
        assertEquals(0, fail)
        // 断网时不累计
        repeat(5) { fail = HealthPolicy.nextFailCount(fail, ok = false, offline = true) }
        assertEquals(0, fail)
        // 连续 3 次 → 移除
        repeat(3) { fail = HealthPolicy.nextFailCount(fail, ok = false, offline = false) }
        assertTrue(HealthPolicy.shouldRemove(fail))

        // 列表过滤与恢复
        val a = Source("b:a", "A", "http://a.example/api", "json", true, order = 1)
        val b = Source("b:b", "B", "http://b.example/api", "json", true, order = 2)
        val removedB = HealthEntity(id = "b:b", kind = HealthEntity.KIND_SOURCE, name = "B", url = b.api, type = "json",
            builtIn = true, latencyMs = -1, failCount = 3, lastCheck = 1, removed = true)
        assertEquals(listOf("b:a"), buildSourceItems(listOf(a, b), listOf(removedB)).map { it.source.id })
        // 恢复 = 删除 health 记录
        assertEquals(listOf("b:a", "b:b"), buildSourceItems(listOf(a, b), emptyList()).map { it.source.id })
    }

    @Test
    fun offlineDetection() {
        assertTrue(HealthPolicy.isOffline(allFailed = true, targetCount = 3, networkValidated = null))
        assertTrue(HealthPolicy.isOffline(allFailed = false, targetCount = 3, networkValidated = false))
        assertFalse(HealthPolicy.isOffline(allFailed = false, targetCount = 3, networkValidated = true))
        // 只有一个源且失败、网络确认正常 → 不是断网，要累计（修复前永远不会移除唯一的坏源）
        assertFalse(HealthPolicy.isOffline(allFailed = true, targetCount = 1, networkValidated = true))
        assertTrue(HealthPolicy.isOffline(allFailed = true, targetCount = 1, networkValidated = null))
    }

    @Test
    fun sourceOrderingByLatency() {
        fun src(id: String, order: Int) = Source(id, id, "http://$id.example/api", "json", true, order = order)
        fun h(id: String, lat: Long, check: Long = 100) = HealthEntity(id = id, kind = HealthEntity.KIND_SOURCE, name = id,
            url = "u", type = "json", builtIn = true, latencyMs = lat, failCount = if (lat < 0) 1 else 0, lastCheck = check, removed = false)
        val list = listOf(src("dead", 1), src("slow", 2), src("good", 3), src("untested", 4))
        val items = buildSourceItems(list, listOf(h("dead", -1), h("slow", 1200), h("good", 30)))
        assertEquals(listOf("good", "slow", "untested", "dead"), items.map { it.source.id })
    }

    @Test
    fun updateJsonParseAndVersionCompare() {
        val info = UpdateLogic.parse("\uFEFF" + """{"versionCode":205,"versionName":"1.0.0-r105","apkUrl":"https://example.invalid/a.apk",
            "size":"3.1 MB","changelog":["a","b"],"force":true,"md5":"abc","extra":1}""")
        assertEquals(205, info.versionCode)
        assertEquals(listOf("a", "b"), info.changelog)
        assertTrue(info.force)
        assertTrue(UpdateLogic.isNewer(info, 107))
        assertFalse(UpdateLogic.isNewer(info, 205))
        assertFalse(UpdateLogic.isNewer(info, 300))
        assertFalse(UpdateLogic.isNewer(info.copy(apkUrl = ""), 1))
        // 首次发布前仓库里的占位 update.json（versionCode 1）不会触发更新
        assertFalse(UpdateLogic.isNewer(UpdateLogic.parse(File("../update.json").takeIf { it.exists() }?.readText()
            ?: """{"versionCode":1,"apkUrl":""}"""), 101))
        // CI: versionCode = 100 + run_number，后一次运行总是更大
        assertTrue(UpdateLogic.isNewer(UpdateInfo(versionCode = 100 + 8, apkUrl = "x"), 100 + 7))
    }

    @Test
    fun md5Check() {
        val f = File.createTempFile("upd", ".apk")
        f.writeText("hello")
        assertEquals("5d41402abc4b2a76b9719d911017c592", UpdateLogic.md5(f))
        assertTrue(UpdateLogic.md5Matches(f, "5D41402ABC4B2A76B9719D911017C592"))
        assertTrue(UpdateLogic.md5Matches(f, ""))
        assertFalse(UpdateLogic.md5Matches(f, "00000000000000000000000000000000"))
        f.delete()
    }
}

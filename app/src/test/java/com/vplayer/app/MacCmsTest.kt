package com.vplayer.app

import com.vplayer.app.data.cms.MacCms
import com.vplayer.app.data.model.PlayUrlParser
import com.vplayer.app.player.UrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacCmsTest {
    private val api = "https://your-api.example/api.php/provide/vod"

    @Test
    fun buildUrls() {
        assertEquals("$api?ac=list&pg=2&t=6", MacCms.listUrl(api, 2, "6"))
        assertEquals("$api?ac=detail&ids=1%2C2", MacCms.detailUrl(api, listOf("1", "2")))
        assertEquals("$api/?ac=detail&pg=1&wd=%E4%BD%A0%E5%A5%BD", MacCms.detailListUrl("$api/", 1, null, "你好"))
        assertEquals("$api?x=1&ac=list&pg=1", MacCms.listUrl("$api?x=1"))
    }

    @Test
    fun parsePlayUrls() {
        val lines = PlayUrlParser.parse(
            "m3u8$$\$web",
            "第01集\$https://a.example/1.m3u8#第02集\$https://a.example/2.m3u8$$\$HD\$https://b.example/play?id=1",
        )
        assertEquals(2, lines.size)
        assertEquals("m3u8", lines[0].name)
        assertEquals(2, lines[0].episodes.size)
        assertEquals("第02集", lines[0].episodes[1].name)
        assertEquals("https://a.example/2.m3u8", lines[0].episodes[1].url)
        assertEquals("web", lines[1].name)
        assertEquals("HD", lines[1].episodes[0].name)
        // 没有名称的集
        val l2 = PlayUrlParser.parse("", "https://x.example/1.mp4#https://x.example/2.mp4")
        assertEquals("线路1", l2[0].name)
        assertEquals("第2集", l2[0].episodes[1].name)
    }

    @Test
    fun parseJsonList() {
        val body = """{"code":1,"page":"2","pagecount":10,"total":200,"list":[
            {"vod_id":12,"vod_name":"示例","vod_pic":"https://img.example/a.jpg","type_id":"6","vod_remarks":"更新至12集",
             "vod_content":"<p>简介&nbsp;内容</p>","vod_play_from":"m3u8","vod_play_url":"第1集${'$'}https://a.example/1.m3u8","vod_score":"8.7"}],
            "class":[{"type_id":1,"type_name":"电影"},{"type_id":"2","type_name":"剧集"}]}"""
        val p = MacCms.parse(body)
        assertEquals(2, p.page)
        assertEquals(10, p.pageCount)
        assertEquals(1, p.list.size)
        assertEquals("12", p.list[0].id)
        assertEquals("简介 内容", p.list[0].content)
        assertEquals("8.7", p.list[0].score)
        assertEquals(1, p.list[0].lines.size)
        assertEquals(listOf("电影", "剧集"), p.categories.map { it.name })
    }

    @Test
    fun directLinks() {
        assertTrue(UrlResolver.isDirect("https://a.example/index.m3u8?token=1"))
        assertTrue(UrlResolver.isDirect("http://a.example/v.mp4"))
        assertFalse(UrlResolver.isDirect("https://v.example/play/abc"))
    }
}

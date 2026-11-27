package com.vplayer.app.data.cms

import android.util.Xml
import com.vplayer.app.data.model.Category
import com.vplayer.app.data.model.Vod
import com.vplayer.app.data.model.VodPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder

/**
 * 苹果CMS v10 采集接口适配：
 *  JSON:  api.php/provide/vod/?ac=list|detail|videolist &pg= &t= &wd= &h= &ids=
 *  XML :  api.php/provide/vod/at/xml/?ac=...
 */
object MacCms {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** XML 解析器工厂（JVM 单元测试中替换为 KXmlParser，因为 android.util.Xml 在本地测试中不可用） */
    @Volatile
    var xmlParserFactory: () -> XmlPullParser = { Xml.newPullParser() }

    /** 去掉用户误填在地址里的 ac/pg/t/wd/h/ids 参数，例如 …/vod/?ac=list */
    fun cleanApi(api: String): String {
        val u = api.trim()
        val q = u.indexOf('?')
        if (q < 0) return u
        val rest = u.substring(q + 1).split('&')
            .filter { it.isNotEmpty() && !Regex("^(ac|pg|t|wd|h|ids)=", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .joinToString("&")
        return if (rest.isEmpty()) u.substring(0, q) else u.substring(0, q) + "?" + rest
    }

    fun buildUrl(api: String, vararg params: Pair<String, String?>): String {
        val base = cleanApi(api)
        val sb = StringBuilder(base)
        var hasQuery = base.contains('?')
        for ((k, v) in params) {
            if (v.isNullOrEmpty()) continue
            sb.append(if (hasQuery) '&' else '?')
            hasQuery = true
            sb.append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        return sb.toString()
    }

    fun listUrl(api: String, pg: Int = 1, typeId: String? = null, hours: Int? = null) =
        buildUrl(api, "ac" to "list", "pg" to pg.toString(), "t" to typeId, "h" to hours?.toString())

    fun detailListUrl(api: String, pg: Int = 1, typeId: String? = null, keyword: String? = null, hours: Int? = null) =
        buildUrl(api, "ac" to "detail", "pg" to pg.toString(), "t" to typeId, "wd" to keyword, "h" to hours?.toString())

    fun searchListUrl(api: String, keyword: String, pg: Int = 1) =
        buildUrl(api, "ac" to "list", "wd" to keyword, "pg" to pg.toString())

    fun detailUrl(api: String, ids: List<String>) = buildUrl(api, "ac" to "detail", "ids" to ids.joinToString(","))

    /** 自动识别 JSON / XML */
    fun parse(body: String): VodPage {
        val t = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return if (t.startsWith("<")) parseXml(t) else parseJson(t)
    }

    private fun JsonObject.s(vararg keys: String): String {
        for (k in keys) {
            val v = (this[k] as? JsonPrimitive)?.contentOrNull
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    private fun JsonObject.i(key: String, def: Int): Int = s(key).toIntOrNull() ?: def

    fun parseJson(body: String): VodPage {
        val root = json.parseToJsonElement(body).jsonObject
        val list = (root["list"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.s("vod_id")
            if (id.isEmpty()) return@mapNotNull null
            Vod(
                id = id,
                name = o.s("vod_name"),
                pic = o.s("vod_pic"),
                typeId = o.s("type_id"),
                typeName = o.s("type_name"),
                remarks = o.s("vod_remarks"),
                year = o.s("vod_year"),
                area = o.s("vod_area"),
                lang = o.s("vod_lang"),
                actor = o.s("vod_actor"),
                director = o.s("vod_director"),
                content = stripHtml(o.s("vod_content", "vod_blurb")),
                score = o.s("vod_score", "vod_douban_score").takeIf { it != "0.0" && it != "0" } ?: "",
                playFrom = o.s("vod_play_from"),
                playUrl = o.s("vod_play_url"),
                time = o.s("vod_time"),
            )
        } ?: emptyList()
        val cats = (root["class"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.s("type_id")
            val name = o.s("type_name")
            if (id.isEmpty() || name.isEmpty()) null else Category(id, name)
        } ?: emptyList()
        return VodPage(
            page = root.i("page", 1),
            pageCount = root.i("pagecount", 1),
            list = list,
            categories = cats,
        )
    }

    fun parseXml(body: String): VodPage {
        val p = xmlParserFactory()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(body))
        var page = 1
        var pageCount = 1
        val vods = mutableListOf<Vod>()
        val cats = mutableListOf<Category>()
        var cur: MutableMap<String, String>? = null
        val froms = mutableListOf<String>()
        val urls = mutableListOf<String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (val name = p.name) {
                    "list" -> {
                        page = p.getAttributeValue(null, "page")?.toIntOrNull() ?: 1
                        pageCount = p.getAttributeValue(null, "pagecount")?.toIntOrNull() ?: 1
                    }
                    "video" -> { cur = mutableMapOf(); froms.clear(); urls.clear() }
                    "dd" -> {
                        froms += p.getAttributeValue(null, "flag") ?: ""
                        urls += p.nextText().trim()
                    }
                    "ty" -> {
                        val id = p.getAttributeValue(null, "id") ?: ""
                        val n = p.nextText().trim()
                        if (id.isNotEmpty() && n.isNotEmpty()) cats += Category(id, n)
                    }
                    "id", "tid", "name", "type", "pic", "lang", "area", "year", "note",
                    "actor", "director", "des", "last", "state" -> {
                        val m = cur
                        if (m != null) m[name] = p.nextText().trim()
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "video") {
                val m = cur
                if (m != null && !m["id"].isNullOrEmpty()) {
                    vods += Vod(
                        id = m["id"]!!, name = m["name"] ?: "", pic = m["pic"] ?: "",
                        typeId = m["tid"] ?: "", typeName = m["type"] ?: "",
                        remarks = m["note"] ?: "", year = m["year"] ?: "", area = m["area"] ?: "",
                        lang = m["lang"] ?: "", actor = m["actor"] ?: "", director = m["director"] ?: "",
                        content = stripHtml(m["des"] ?: ""), time = m["last"] ?: "",
                        playFrom = froms.joinToString("$$$"), playUrl = urls.joinToString("$$$"),
                    )
                }
                cur = null
            }
            ev = p.next()
        }
        return VodPage(page, pageCount, vods, cats)
    }

    private val tagRegex = Regex("<[^>]+>")
    fun stripHtml(s: String): String = s.replace(tagRegex, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").trim()
}

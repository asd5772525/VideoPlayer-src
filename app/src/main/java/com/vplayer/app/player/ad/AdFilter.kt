package com.vplayer.app.player.ad

import java.net.URI

/**
 * 每个源的去广告规则（来自 config.json 的 sources[].adFilter，经全局开关合并后得到）。
 * - smart：启发式识别 DISCONTINUITY 之间的插播片段
 * - keywords / regex：分片地址命中即视为广告（大小写不敏感的包含 / Java 正则）
 * - maxAdSeconds：一段插播的最长总时长，超过的不算广告
 */
data class AdRule(
    val smart: Boolean = true,
    val keywords: List<String> = emptyList(),
    val regex: String = "",
    val maxAdSeconds: Double = 30.0,
) {
    companion object {
        /** 安全上限：一次最多去掉总时长的 20% */
        const val MAX_REMOVE_RATIO = 0.20
    }
}

data class AdFilterResult(
    val text: String,
    val removedSegments: Int,
    val removedSeconds: Double,
    val totalSeconds: Double,
    val reason: String = "",
) {
    val changed get() = removedSegments > 0
}

/**
 * M3U8 插播广告过滤（纯文本处理，播放与下载共用，便于单元测试）。
 *
 * 只处理点播的 media playlist（有 #EXT-X-ENDLIST，没有 #EXT-X-STREAM-INF），直播和 master 原样返回。
 * 判定方式：
 * 1. 按 #EXT-X-DISCONTINUITY 把分片分成若干「段」，总时长最长的一段视为正片（永不删除）
 * 2. 关键词 / 正则：分片地址命中即标记为广告（按分片）
 * 3. 启发式（按段）：段总时长 ≤ maxAdSeconds，且与正片相比
 *    目录（域名 + 路径前缀）不同 → 2 分；文件名模式不同 → 2 分；分片时长规律不同 → 1 分；≥ 2 分判为广告。
 *    也就是说单凭“时长不同”永远不会删。
 * 4. 安全阈值：删除总时长超过全片 20% 时，先放弃启发式结果；仍超过则什么都不删。
 * 删除时只去掉分片本身（#EXTINF / #EXT-X-BYTERANGE / #EXT-X-PROGRAM-DATE-TIME + 地址），
 * #EXT-X-KEY / #EXT-X-MAP 等状态标签原样保留，保证后续分片解密不受影响；被删段两侧多余的 DISCONTINUITY 合并为一个。
 */
object AdFilter {

    private class Seg(
        val tags: MutableList<String>,  // 分片前的标签（含 EXTINF）
        val uri: String,                // 原始地址行
        val abs: String,                // 解析后的绝对地址
        val duration: Double,
        val run: Int,
    )

    private val stateTags = listOf("#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-DISCONTINUITY-SEQUENCE")
    private val dropWithSegment = listOf("#EXTINF", "#EXT-X-BYTERANGE", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-GAP", "#EXT-X-BITRATE")

    fun filter(text: String, baseUrl: String, rule: AdRule): AdFilterResult {
        val unchanged = { reason: String, total: Double -> AdFilterResult(text, 0, 0.0, total, reason) }
        if (!text.contains("#EXTM3U") || text.contains("#EXT-X-STREAM-INF")) return unchanged("not media playlist", 0.0)
        if (!text.contains("#EXT-X-ENDLIST")) return unchanged("live playlist", 0.0)

        // ---- 解析 ----
        val header = mutableListOf<String>()
        val segs = mutableListOf<Seg>()
        val trailer = mutableListOf<String>()
        var pending = mutableListOf<String>()
        var run = 0
        var sawSegment = false
        var duration = 0.0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line == "#EXT-X-DISCONTINUITY" -> { if (sawSegment) run++; pending += line }
                    line.startsWith("#EXTINF:") -> {
                        duration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                        pending += line
                    }
                    line == "#EXT-X-ENDLIST" -> trailer += line
                    !sawSegment && pending.none { it.startsWith("#EXTINF") } && isHeaderTag(line) -> header += line
                    else -> pending += line
                }
            } else {
                segs += Seg(pending, line, resolve(baseUrl, line), duration, run)
                pending = mutableListOf()
                sawSegment = true
                duration = 0.0
            }
        }
        val tail = pending // ENDLIST 前的零散标签
        val total = segs.sumOf { it.duration }
        if (segs.size < 3 || total <= 0) return unchanged("too short", total)

        val runs = segs.groupBy { it.run }
        val main = runs.maxByOrNull { (_, l) -> l.sumOf { it.duration } }!!.key
        val mainSegs = runs[main]!!

        // ---- 关键词 / 正则（按分片） ----
        val kw = rule.keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val re = rule.regex.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        val byRule = HashSet<Seg>()
        if (kw.isNotEmpty() || re != null) {
            segs.forEach { s ->
                if (s.run == main) return@forEach
                val u = s.abs.lowercase()
                if (kw.any { u.contains(it) } || (re != null && re.containsMatchIn(s.abs))) byRule += s
            }
        }

        // ---- 启发式（按段） ----
        val bySmart = HashSet<Seg>()
        if (rule.smart && runs.size >= 2) {
            val mainDir = dominant(mainSegs.map { dirOf(it.abs) })
            val mainPat = dominant(mainSegs.map { patternOf(it.abs) })
            val mainDur = median(mainSegs.map { it.duration })
            for ((r, list) in runs) {
                if (r == main) continue
                val dur = list.sumOf { it.duration }
                if (dur > rule.maxAdSeconds) continue
                var score = 0
                val dir = dominant(list.map { dirOf(it.abs) })
                if (mainDir != null && dir?.value != mainDir.value) score += 2
                val pat = dominant(list.map { patternOf(it.abs) })
                if (mainPat != null && pat?.value != mainPat.value) score += 2
                if (mainSegs.size >= 3) {
                    val d = median(list.map { it.duration })
                    if (mainDur > 0 && kotlin.math.abs(d - mainDur) / mainDur > 0.4) score += 1
                }
                if (score >= 2) bySmart += list
            }
        }

        // ---- 安全阈值 ----
        val limit = total * AdRule.MAX_REMOVE_RATIO
        var remove: Set<Seg> = byRule + bySmart
        var reason = "rule+smart"
        if (remove.sumOf { it.duration } > limit) { remove = byRule; reason = "smart over limit" }
        if (remove.sumOf { it.duration } > limit) return unchanged("over ${(AdRule.MAX_REMOVE_RATIO * 100).toInt()}% limit", total)
        if (remove.isEmpty()) return unchanged("no ads", total)

        // ---- 重建 ----
        val out = StringBuilder()
        header.forEach { out.append(it).append('\n') }
        var pendingDisc = false
        var lastWasKept = false
        for (s in segs) {
            val hasDisc = s.tags.any { it == "#EXT-X-DISCONTINUITY" }
            if (hasDisc) pendingDisc = true
            if (s in remove) {
                // 保留状态标签（解密 key / init 段），丢掉分片本身
                s.tags.filter { t -> stateTags.any { t.startsWith(it) } }.forEach { out.append(it).append('\n') }
                continue
            }
            if (pendingDisc && lastWasKept) out.append("#EXT-X-DISCONTINUITY\n")
            pendingDisc = false
            s.tags.filter { it != "#EXT-X-DISCONTINUITY" }.forEach { out.append(it).append('\n') }
            out.append(s.uri).append('\n')
            lastWasKept = true
        }
        tail.filter { it != "#EXT-X-DISCONTINUITY" }.forEach { out.append(it).append('\n') }
        trailer.forEach { out.append(it).append('\n') }
        return AdFilterResult(out.toString(), remove.size, remove.sumOf { it.duration }, total, reason)
    }

    private fun isHeaderTag(l: String) = l.startsWith("#EXTM3U") || l.startsWith("#EXT-X-VERSION") ||
        l.startsWith("#EXT-X-TARGETDURATION") || l.startsWith("#EXT-X-MEDIA-SEQUENCE") || l.startsWith("#EXT-X-PLAYLIST-TYPE") ||
        l.startsWith("#EXT-X-ALLOW-CACHE") || l.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || l.startsWith("#EXT-X-START") ||
        l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")

    private fun resolve(base: String, ref: String): String = try {
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URI(base).resolve(ref.trim()).toString()
    } catch (e: Exception) { ref }

    /** 域名 + 路径前缀（去掉文件名） */
    internal fun dirOf(url: String): String {
        val noQ = url.substringBefore('?').substringBefore('#')
        return noQ.substringBeforeLast('/', noQ)
    }

    /** 文件名模式：数字串 → #，长哈希 → H<长度>，保留扩展名 */
    internal fun patternOf(url: String): String {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        val stem = name.substringBeforeLast('.', name)
        val p = if (stem.length >= 16 && stem.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) "H${stem.length}"
        else stem.replace(Regex("\\d+"), "#")
        return "$p.$ext"
    }

    private class Dominant(val value: String)

    /** 出现最多的值；占比不到 60% 视为没有明显规律（返回 null，不参与判断） */
    private fun dominant(values: List<String>): Dominant? {
        if (values.isEmpty()) return null
        val (v, n) = values.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
        return if (n >= values.size * 0.6) Dominant(v) else null
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

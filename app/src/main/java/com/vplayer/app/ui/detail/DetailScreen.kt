package com.vplayer.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vplayer.app.data.model.Vod
import com.vplayer.app.ui.components.EmptyBox
import com.vplayer.app.ui.components.LoadingBox
import com.vplayer.app.ui.components.Poster
import com.vplayer.app.ui.components.SectionHeader
import com.vplayer.app.ui.components.appViewModel
import com.vplayer.app.ui.components.formatTime
import com.vplayer.app.ui.player.PlayerActivity
import com.vplayer.app.ui.theme.Accent
import com.vplayer.app.ui.theme.Bad
import com.vplayer.app.ui.theme.PosterGradients
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(sourceId: String, vodId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "detail|$sourceId|$vodId") { DetailViewModel(it, sourceId, vodId) }
    val vod by vm.vod.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val line by vm.line.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val reverse by vm.reverse.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val fav by vm.isFav.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDownload by remember { mutableStateOf(false) }

    fun play(lineIdx: Int, ep: Int) {
        PlayerActivity.start(ctx, sourceId, vodId, lineIdx, ep)
    }

    val v = vod
    if (v == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            if (error != null) EmptyBox("加载失败：$error") else LoadingBox()
        }
        return
    }
    val lineIdx = line.coerceIn(0, (v.lines.size - 1).coerceAtLeast(0))
    val episodes = v.lines.getOrNull(lineIdx)?.episodes.orEmpty()
    val ordered = episodes.indices.toList().let { if (reverse) it.reversed() else it }
    val currentEp = history?.epIndex ?: -1

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header(v, onBack) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val h = history
                    Button(
                        onClick = {
                            if (episodes.isEmpty()) Toast.makeText(ctx, "暂无播放地址", Toast.LENGTH_SHORT).show()
                            else scope.launch { val (l, e, _) = vm.resumeTarget(); play(l, e) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(2f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Text(
                            if (h != null) "继续 ${if (episodes.size > 1) h.epName else ""} ${formatTime(h.position)}" else "立即播放",
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SmallAction(Modifier.weight(1f), "下载", Icons.Default.Download) { showDownload = true }
                    SmallAction(Modifier.weight(0.6f), null, if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        tint = if (fav) Accent else null) { vm.toggleFav() }
                }
                var expanded by remember { mutableStateOf(false) }
                if (v.content.isNotBlank()) Text(
                    "简介：" + v.content, fontSize = 12.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
                if (v.actor.isNotBlank()) Text("主演：${v.actor}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))

                if (v.lines.isEmpty()) {
                    EmptyBox("该影片暂无播放地址")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionHeader("播放线路") }
                        TextButton(onClick = { vm.testLines() }) { Text("重新测速", fontSize = 12.sp, color = Accent) }
                    }
                    // 按测速结果排序：最快在前，超时置底
                    val sortedLines = v.lines.indices.sortedBy { i ->
                        when (val ms = latency[i]) { null -> 500_000L + i; -1L -> 1_000_000L + i; else -> ms }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sortedLines.forEach { i ->
                            val ms = latency[i]
                            val label = v.lines[i].name + " · " + when {
                                !latency.containsKey(i) -> "—"
                                ms == null -> "测速中"
                                ms < 0 -> "超时"
                                else -> "${ms}ms"
                            }
                            val sel = i == lineIdx
                            Text(
                                label, fontSize = 12.sp,
                                color = when { sel -> Accent; ms != null && ms < 0 -> Bad; else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) },
                                modifier = Modifier
                                    .then(if (sel) Modifier.border(1.dp, Accent, RoundedCornerShape(14.dp)) else Modifier)
                                    .background(if (sel) Accent.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                                    .clickable { vm.selectLine(i) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionHeader("选集 · 共 ${episodes.size} 集") }
                        Row(Modifier.clickable { vm.reverse.value = !reverse }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (reverse) "正序" else "倒序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Default.SwapVert, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        itemsIndexed(ordered, key = { _, it -> it }) { pos, i ->
            val ep = episodes[i]
            val col = pos % 5
            val on = i == currentEp
            val done = i in watched && !on
            Box(
                Modifier.padding(start = if (col == 0) 16.dp else 0.dp, end = if (col == 4) 16.dp else 0.dp)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Accent else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { play(lineIdx, i) }
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shortEpName(ep.name, i), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = when { on -> Color.White; done -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f); else -> MaterialTheme.colorScheme.onSurface },
                )
                if (i in downloaded) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF3ECF8E)))
            }
        }
    }

    if (showDownload && episodes.isNotEmpty()) {
        DownloadSheet(v, lineIdx, downloaded, onDismiss = { showDownload = false }) { eps ->
            vm.download(lineIdx, eps)
            Toast.makeText(ctx, "已加入下载队列（${eps.size} 集）", Toast.LENGTH_SHORT).show()
            showDownload = false
        }
    }
}

private fun shortEpName(name: String, i: Int): String {
    val m = Regex("第\\s*0*(\\d+)\\s*[集期话]").find(name)
    if (m != null) return m.groupValues[1].padStart(2, '0')
    return if (name.length <= 6) name else name.take(6)
}

@Composable
private fun SmallAction(modifier: Modifier, text: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color? = null, onClick: () -> Unit) {
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, text, Modifier.size(18.dp), tint = tint ?: MaterialTheme.colorScheme.onSurface)
        if (text != null) { Spacer(Modifier.width(4.dp)); Text(text, fontSize = 12.sp) }
    }
}

@Composable
private fun Header(v: Vod, onBack: () -> Unit) {
    val colors = PosterGradients[(v.name.hashCode() and 0x7fffffff) % PosterGradients.size]
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors[0], MaterialTheme.colorScheme.background)))
    ) {
        Column(Modifier.statusBarsPadding()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                Poster(
                    v.pic, v.name,
                    Modifier.width(96.dp).height(136.dp).clip(RoundedCornerShape(10.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(v.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    val meta = listOf(v.year, v.typeName, v.area).filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    if (v.score.isNotBlank()) Row {
                        Text("评分 ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                        Text(v.score, fontSize = 12.sp, color = Accent)
                    }
                    val total = v.lines.maxOfOrNull { it.episodes.size } ?: 0
                    if (total > 1) Text("共 $total 集", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    if (v.remarks.isNotBlank()) Text(v.remarks, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    if (v.director.isNotBlank()) Text("导演：${v.director}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloadSheet(v: Vod, lineIdx: Int, downloaded: Set<Int>, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
    val eps = v.lines[lineIdx].episodes
    val selected = remember { mutableStateListOf<Int>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择要下载的剧集 · ${v.lines[lineIdx].name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (selected.size == eps.size) selected.clear() else { selected.clear(); selected.addAll(eps.indices) }
                }) { Text(if (selected.size == eps.size) "取消全选" else "全选", color = Accent) }
            }
            FlowRow(
                Modifier.heightIn(max = 360.dp).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                eps.forEachIndexed { i, e ->
                    val on = i in selected
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (on) Accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { if (on) selected.remove(i) else selected.add(i) }
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { if (on) selected.remove(i) else selected.add(i) }, colors = CheckboxDefaults.colors(checkedColor = Accent))
                        Text(e.name + if (i in downloaded) " ✓" else "", fontSize = 12.sp)
                    }
                }
            }
            Button(
                onClick = { onConfirm(selected.sorted()) }, enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.fillMaxWidth(),
            ) { Text("开始下载（${selected.size}）") }
        }
    }
}

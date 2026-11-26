package com.vplayer.app.ui.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vplayer.app.data.db.DownloadEntity
import com.vplayer.app.download.StorageInfo
import com.vplayer.app.ui.components.Badge
import com.vplayer.app.ui.components.EmptyBox
import com.vplayer.app.ui.components.Poster
import com.vplayer.app.ui.components.container
import com.vplayer.app.ui.components.formatBytes
import com.vplayer.app.ui.player.PlayerActivity
import com.vplayer.app.ui.theme.Accent
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen() {
    val c = container()
    val all by c.downloads.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    var storage by remember { mutableStateOf<StorageInfo?>(null) }
    val doneCount = all.count { it.status == DownloadEntity.STATUS_DONE }
    LaunchedEffect(doneCount) { storage = runCatching { c.downloads.storage() }.getOrNull() }

    val active = all.filter { it.status != DownloadEntity.STATUS_DONE }
    val done = all.filter { it.status == DownloadEntity.STATUS_DONE }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("下载", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (editing) "完成" else "编辑", fontSize = 13.sp, color = if (editing) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.combinedClickable { editing = !editing })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            listOf("下载中 ${active.size}", "已完成 ${done.size}").forEachIndexed { i, t ->
                Column(Modifier.combinedClickable { tab = i }) {
                    Text(t, fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                        color = if (tab == i) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (tab == i) Box(Modifier.padding(top = 4.dp).width(24.dp).height(3.dp).background(Accent, CircleShape))
                }
            }
        }
        storage?.let { s ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("存储 已用 ${formatBytes(s.usedByApp)} / 可用 ${formatBytes(s.free)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { if (s.total > 0) ((s.total - s.free).toFloat() / s.total) else 0f },
                    color = Accent, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(CircleShape),
                )
            }
        }
        val list = if (tab == 0) active else done
        if (list.isEmpty()) EmptyBox(if (tab == 0) "没有正在下载的任务\n在详情页点击「下载」即可离线缓存" else "还没有已完成的下载")
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            when (d.status) {
                                DownloadEntity.STATUS_DONE -> PlayerActivity.start(ctx, d.sourceId, d.vodId, 0, d.epIndex, d.localPath, d.name)
                                DownloadEntity.STATUS_RUNNING, DownloadEntity.STATUS_QUEUED -> scope.launch { c.downloads.pause(d.id) }
                                else -> scope.launch { c.downloads.resume(d.id) }
                            }
                        },
                        onLongClick = { confirmDelete = d },
                    ).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(110.dp).height(64.dp).clip(RoundedCornerShape(8.dp))) {
                        Poster(d.pic, d.name, Modifier.fillMaxSize(), widthPx = 300, heightPx = 180)
                        if (d.status == DownloadEntity.STATUS_DONE) Badge(formatBytes(d.totalBytes), Modifier.align(Alignment.BottomEnd).padding(4.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("${d.name} · ${d.epName}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle(d), fontSize = 11.sp, color = if (d.status == DownloadEntity.STATUS_FAILED) Color(0xFFFF5A5A) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (d.status != DownloadEntity.STATUS_DONE) LinearProgressIndicator(
                            progress = { d.progress.coerceIn(0f, 1f) },
                            color = if (d.status == DownloadEntity.STATUS_RUNNING) Accent else Color(0xFF666677),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(CircleShape),
                        )
                    }
                    if (editing) IconButton(onClick = { confirmDelete = d }) { Icon(Icons.Default.Delete, "删除", Modifier.size(20.dp)) }
                }
            }
        }
    }
    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除下载") },
            text = { Text("确定删除「${d.name} · ${d.epName}」及已下载的文件吗？") },
            confirmButton = { TextButton(onClick = { scope.launch { c.downloads.delete(d.id) }; confirmDelete = null }) { Text("删除", color = Accent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

private fun speed(bps: Long) = if (bps > 1024 * 1024) "%.1f MB/s".format(bps / 1048576f) else "${bps / 1024} KB/s"

private fun subtitle(d: DownloadEntity): String = when (d.status) {
    DownloadEntity.STATUS_DONE -> "离线可播 · ${d.kind.uppercase()}"
    DownloadEntity.STATUS_PAUSED -> "已暂停 · 点击继续"
    DownloadEntity.STATUS_FAILED -> "失败：${d.error} · 点击重试"
    DownloadEntity.STATUS_QUEUED -> "等待中"
    else -> {
        val kind = d.kind.uppercase().ifBlank { "解析中" }
        if (d.segTotal > 0) "$kind · ${d.segDone}/${d.segTotal} 分片 · ${speed(d.speedBps)}"
        else "$kind · ${formatBytes(d.downloadedBytes)}${if (d.totalBytes > 0) " / " + formatBytes(d.totalBytes) else ""} · ${speed(d.speedBps)}"
    }
}

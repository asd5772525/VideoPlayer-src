package com.vplayer.app.ui.me

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.vplayer.app.BuildConfig
import com.vplayer.app.data.db.FavoriteEntity
import com.vplayer.app.ui.components.Badge
import com.vplayer.app.ui.components.EmptyBox
import com.vplayer.app.ui.components.Group
import com.vplayer.app.ui.components.OptionRow
import com.vplayer.app.ui.components.Poster
import com.vplayer.app.ui.components.SwitchRow
import com.vplayer.app.ui.components.container
import com.vplayer.app.ui.components.formatTime
import com.vplayer.app.ui.player.PlayerActivity
import com.vplayer.app.ui.theme.Accent
import kotlinx.coroutines.launch

@Composable
fun MeScreen(onHistory: () -> Unit, onFavorites: () -> Unit, onSettings: () -> Unit) {
    val c = container()
    val s by c.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var themeDialog by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("我的", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
        Group {
            OptionRow("观看历史", onClick = onHistory, value = "")
            OptionRow("我的收藏", onClick = onFavorites, value = "")
            OptionRow("深色 / 浅色主题", value = listOf("跟随系统", "深色", "浅色")[s.theme], onClick = { themeDialog = true })
            SwitchRow("应用锁", s.appLock, subtitle = "启动时需输入 4 位数字密码，可选") { on ->
                if (on) pinDialog = true else scope.launch { c.settings.setAppLock(false, "") }
            }
            OptionRow("检查更新", value = "v${BuildConfig.VERSION_NAME}", onClick = {
                scope.launch {
                    Toast.makeText(ctx, "正在检查更新…", Toast.LENGTH_SHORT).show()
                    c.updates.check(manual = true)
                }
            })
            OptionRow("更新地址", subtitle = c.updates.urls().firstOrNull() ?: "未配置", value = "远程 JSON", onClick = { urlDialog = true })
        }
        Group("更多") {
            OptionRow("播放与接口设置", subtitle = "数据源 · 解析接口 · 播放器 · 下载", onClick = onSettings, value = "")
            OptionRow("清除缓存", subtitle = "清除接口与图片缓存（不影响下载与历史）", onClick = {
                scope.launch {
                    c.cms.clearCache()
                    ctx.cacheDir.resolve("img").deleteRecursively()
                    Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
                }
            })
        }
        Text(
            "内置配置版本 ${c.config.configVersion} · 内置源 ${c.sources.builtInSources.size} 个",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
        )
    }

    if (themeDialog) AlertDialog(
        onDismissRequest = { themeDialog = false },
        title = { Text("主题") },
        text = {
            Column {
                listOf("跟随系统", "深色", "浅色").forEachIndexed { i, t ->
                    Text(t, color = if (i == s.theme) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().combinedClickableCompat { scope.launch { c.settings.setTheme(i) }; themeDialog = false }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
    )
    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text("设置应用锁密码") },
            text = {
                OutlinedTextField(pin, { if (it.length <= 4 && it.all(Char::isDigit)) pin = it }, label = { Text("4 位数字") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = pin.length == 4, onClick = { scope.launch { c.settings.setAppLock(true, pin) }; pinDialog = false }) { Text("确定", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text("取消") } },
        )
    }
    if (urlDialog) {
        var url by remember { mutableStateOf(s.updateUrlOverride) }
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("更新地址（update.json）") },
            text = {
                Column {
                    Text("留空使用内置地址（GitHub raw / jsDelivr 镜像）。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(url, { url = it }, placeholder = { Text("https://…/update.json") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { c.settings.setUpdateUrl(url) }; urlDialog = false }) { Text("保存", color = Accent) } },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
fun BackTitle(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val c = container()
    val list by c.db.playbackDao().observeHistoryList().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackTitle("观看历史", onBack) { TextButton(onClick = { confirmClear = true }) { Text("清空", color = Accent) } }
        if (list.isEmpty()) EmptyBox("还没有观看记录")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(list, key = { it.vodKey }) { h ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { PlayerActivity.start(ctx, h.sourceId, h.vodId, h.lineIndex, h.epIndex) },
                        onLongClick = { scope.launch { c.db.playbackDao().deleteHistory(h.vodKey) } },
                    ).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Poster(h.pic, h.name, Modifier.width(110.dp).height(64.dp).clip(RoundedCornerShape(8.dp)), widthPx = 300, heightPx = 180)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(h.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (h.epCount > 1) "看到 ${h.epName} · " else "看到 ") + "${formatTime(h.position)} / ${formatTime(h.duration)}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                        )
                        Text("长按删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空观看历史？") },
        confirmButton = { TextButton(onClick = { scope.launch { c.db.playbackDao().clearHistory() }; confirmClear = false }) { Text("清空", color = Accent) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(onBack: () -> Unit, onOpen: (FavoriteEntity) -> Unit) {
    val c = container()
    val list by c.db.favoriteDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var remove by remember { mutableStateOf<FavoriteEntity?>(null) }
    var newestFirst by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }

    // 打开收藏页时检查更新：批量请求详情，集数变多则显示“更新”角标
    LaunchedEffect(Unit) {
        checking = true
        runCatching {
            val favs = c.db.favoriteDao().all()
            favs.groupBy { it.sourceId }.forEach { (sid, items) ->
                val src = c.sources.byId(sid) ?: return@forEach
                val vods = runCatching { c.cms.details(src, items.map { it.vodId }) }.getOrDefault(emptyList()).associateBy { it.id }
                items.forEach { f ->
                    val v = vods[f.vodId] ?: return@forEach
                    val n = v.lines.maxOfOrNull { it.episodes.size } ?: 0
                    if (n > f.epCount) c.db.favoriteDao().upsert(f.copy(latestEpCount = n, hasUpdate = true, remarks = v.remarks))
                }
            }
        }
        checking = false
    }
    val sorted = if (newestFirst) list.sortedByDescending { it.createdAt } else list.sortedBy { it.createdAt }
    Column(Modifier.fillMaxSize()) {
        BackTitle("我的收藏" + if (checking) " · 检查更新中" else "", onBack) {
            TextButton(onClick = { newestFirst = !newestFirst }) { Text(if (newestFirst) "最新收藏 ⇅" else "最早收藏 ⇅", color = Accent, fontSize = 12.sp) }
        }
        if (list.isEmpty()) EmptyBox("还没有收藏，在详情页点 ♡ 收藏")
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(sorted, key = { it.vodKey }) { f ->
                Column(Modifier.combinedClickable(onClick = {
                    if (f.hasUpdate) scope.launch { c.db.favoriteDao().upsert(f.copy(hasUpdate = false, epCount = f.latestEpCount)) }
                    onOpen(f)
                }, onLongClick = { remove = f })) {
                    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp))) {
                        Poster(f.pic, f.name, Modifier.fillMaxSize())
                        if (f.hasUpdate) Badge("更新至${f.latestEpCount}", Modifier.align(Alignment.TopStart).padding(5.dp))
                        else if (f.remarks.isNotBlank()) Badge(f.remarks, Modifier.align(Alignment.BottomEnd).padding(5.dp))
                    }
                    Text(f.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    remove?.let { f ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("取消收藏") },
            text = { Text("从收藏中移除「${f.name}」？") },
            confirmButton = { TextButton(onClick = { scope.launch { c.db.favoriteDao().delete(f.vodKey) }; remove = null }) { Text("移除", color = Accent) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } },
        )
    }
}

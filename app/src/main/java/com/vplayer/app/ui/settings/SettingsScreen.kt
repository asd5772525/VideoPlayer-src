package com.vplayer.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vplayer.app.data.cms.ParseItem
import com.vplayer.app.data.cms.SourceItem
import com.vplayer.app.data.cms.SourceRepository
import com.vplayer.app.ui.category.Chip
import com.vplayer.app.ui.components.Group
import com.vplayer.app.ui.components.LatencyBadge
import com.vplayer.app.ui.components.OptionRow
import com.vplayer.app.ui.components.SwitchRow
import com.vplayer.app.ui.components.container
import com.vplayer.app.ui.me.BackTitle
import com.vplayer.app.ui.theme.Accent
import com.vplayer.app.ui.theme.Bad
import com.vplayer.app.ui.theme.Ok
import kotlinx.coroutines.launch

private sealed interface Dlg {
    data object AddSource : Dlg
    data class EditSource(val item: SourceItem) : Dlg
    data object Import : Dlg
    data object AddParse : Dlg
    data class EditParse(val item: ParseItem) : Dlg
    data class IntSlider(val title: String, val value: Int, val range: IntRange, val unit: String, val onSave: (Int) -> Unit) : Dlg
}

@Composable
private fun StatusDot(ok: Boolean?) {
    Box(Modifier.size(8.dp).background(when (ok) { true -> Ok; false -> Bad; null -> Color.Gray }, CircleShape))
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = container()
    val s by c.settings.state.collectAsStateWithLifecycle()
    val sources by c.sources.sources.collectAsStateWithLifecycle()
    val parses by c.sources.parses.collectAsStateWithLifecycle()
    val current by c.sources.current.collectAsStateWithLifecycle()
    val removed by c.sources.removed.collectAsStateWithLifecycle(initialValue = emptyList())
    val testing by c.health.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var dlg by remember { mutableStateOf<Dlg?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackTitle("播放与接口设置", onBack) {
            if (testing) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), color = Accent, strokeWidth = 2.dp)
            TextButton(onClick = { scope.launch { c.health.checkAll(force = true) } }, enabled = !testing) {
                Text(if (testing) "测速中" else "一键测速", color = Accent)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Group("数据源（苹果CMS 兼容 JSON/XML 接口，按响应速度排序）") {
                if (sources.isEmpty()) OptionRow("暂无数据源", "可添加本地源，或由管理员在后台配置并发布新版本")
                sources.forEach { item ->
                    val src = item.source
                    OptionRow(
                        title = src.name + (if (src.id == current?.id) "（当前）" else "") + (if (src.builtIn) " · 内置" else " · 本地"),
                        subtitle = src.api + if (item.failCount > 0) "  · 连续失败 ${item.failCount} 次" else "",
                        onClick = { dlg = Dlg.EditSource(item) },
                        leading = { StatusDot(if (item.tested) item.ok else null) },
                    ) { LatencyBadge(item.latencyMs, item.tested) }
                }
                OptionRow("＋ 添加数据源", "填写接口地址，自动识别 JSON / XML", onClick = { dlg = Dlg.AddSource })
                OptionRow("订阅导入", "从订阅 JSON 地址批量导入源与解析", onClick = { dlg = Dlg.Import })
            }
            if (removed.isNotEmpty()) Group("已自动移除的源（连续 3 次测速失败）") {
                removed.forEach { h ->
                    OptionRow(h.name + if (h.kind == "parse") " · 解析" else "", h.url) {
                        TextButton(onClick = { scope.launch { c.sources.restore(h) } }) { Text("恢复", color = Accent) }
                    }
                }
            }
            Group("解析接口") {
                parses.forEach { item ->
                    val p = item.parse
                    OptionRow(
                        p.name + (if (p.builtIn) " · 内置" else " · 本地") + " · " + (if (p.type == "webview") "网页嗅探" else "JSON"),
                        p.url, onClick = { dlg = Dlg.EditParse(item) },
                        leading = { StatusDot(if (item.lastCheck > 0) item.ok else null) },
                    ) { LatencyBadge(item.latencyMs, item.lastCheck > 0) }
                }
                OptionRow("＋ 添加解析接口", "格式：解析地址前缀，播放地址会拼接在后面", onClick = { dlg = Dlg.AddParse })
                SwitchRow("直链优先（m3u8/mp4 不走解析）", s.directFirst) { v -> scope.launch { c.settings.setDirectFirst(v) } }
                SwitchRow("自动测速选择最快线路", s.autoSpeedTest) { v -> scope.launch { c.settings.setAutoSpeedTest(v) } }
            }
            Group("播放器") {
                OptionRow("内核", value = "ExoPlayer（Media3）")
                SwitchRow("硬件解码", s.hwDecode, "关闭后优先软解，兼容性更好但更耗电") { v -> scope.launch { c.settings.setHwDecode(v) } }
                SwitchRow("预加载下一集", s.preloadNext) { v -> scope.launch { c.settings.setPreloadNext(v) } }
                SwitchRow("去除插播广告", s.adFilter, "自动识别 M3U8 里插在正片中间的短广告片段（保守策略，最多去掉 20%），播放与下载都生效") { v ->
                    scope.launch { c.settings.setAdFilter(v) }
                }
                SwitchRow("播放失败自动换线", s.autoFailover, "出错或卡顿超过 8 秒时，自动换下一个解析 / 线路，保留进度") { v ->
                    scope.launch { c.settings.setAutoFailover(v) }
                }
                OptionRow("缓冲时长", value = "${s.bufferSeconds} 秒", onClick = {
                    dlg = Dlg.IntSlider("缓冲时长", s.bufferSeconds, 10..120, "秒") { v -> scope.launch { c.settings.setBufferSeconds(v) } }
                })
                SwitchRow("记忆播放进度", s.rememberProgress) { v -> scope.launch { c.settings.setRememberProgress(v) } }
                SwitchRow("自动下一集", s.autoNext) { v -> scope.launch { c.settings.setAutoNext(v) } }
                OptionRow("默认倍速") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                            Text("${sp}x", fontSize = 12.sp, color = if (sp == s.defaultSpeed) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { scope.launch { c.settings.setDefaultSpeed(sp) } }.padding(2.dp))
                        }
                    }
                }
                OptionRow("默认跳过片头", value = "${s.skipIntro} 秒", onClick = {
                    dlg = Dlg.IntSlider("默认跳过片头", s.skipIntro, 0..300, "秒") { v -> scope.launch { c.settings.setSkipIntro(v) } }
                })
                OptionRow("默认跳过片尾", value = "${s.skipOutro} 秒", onClick = {
                    dlg = Dlg.IntSlider("默认跳过片尾", s.skipOutro, 0..300, "秒") { v -> scope.launch { c.settings.setSkipOutro(v) } }
                })
                OptionRow("边播边存缓存", value = "${s.cacheSizeMb} MB", onClick = {
                    dlg = Dlg.IntSlider("边播边存缓存（重启生效）", s.cacheSizeMb, 128..4096, "MB") { v -> scope.launch { c.settings.setCacheSizeMb(v) } }
                })
            }
            Group("下载") {
                OptionRow("下载线程数（每个任务）", value = "${s.downloadThreads}", onClick = {
                    dlg = Dlg.IntSlider("下载线程数", s.downloadThreads, 1..16, "") { v -> scope.launch { c.settings.setDownloadThreads(v) } }
                })
                OptionRow("最大同时下载任务", value = "${s.maxDownloadTasks}", onClick = {
                    dlg = Dlg.IntSlider("最大同时下载任务", s.maxDownloadTasks, 1..5, "") { v -> scope.launch { c.settings.setMaxDownloadTasks(v) } }
                })
                SwitchRow("仅 Wi-Fi 下载", s.wifiOnly) { v -> scope.launch { c.settings.setWifiOnly(v) } }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    when (val d = dlg) {
        null -> {}
        Dlg.AddSource -> SourceEditDialog("添加数据源", "", "", onDismiss = { dlg = null }, testUrl = { c.health.testSource(it) }) { name, api ->
            scope.launch {
                val ok = c.sources.addLocalSource(name, api)
                Toast.makeText(ctx, if (ok) "已添加" else "地址为空或已存在", Toast.LENGTH_SHORT).show()
                if (ok) c.health.checkAll(force = true)
            }
            dlg = null
        }
        is Dlg.EditSource -> {
            val src = d.item.source
            if (src.builtIn) AlertDialog(
                onDismissRequest = { dlg = null },
                title = { Text(src.name) },
                text = { Text("内置源（只读）\n${src.api}\n\n内置源由管理员在后台配置，随新版本更新。") },
                confirmButton = { TextButton(onClick = { scope.launch { c.sources.select(src.id) }; dlg = null }) { Text("设为当前", color = Accent) } },
                dismissButton = { TextButton(onClick = { dlg = null }) { Text("关闭") } },
            ) else SourceEditDialog("编辑数据源", src.name, src.api, onDismiss = { dlg = null }, testUrl = { c.health.testSource(it) },
                extra = {
                    Row {
                        TextButton(onClick = { scope.launch { c.sources.select(src.id) }; dlg = null }) { Text("设为当前", color = Accent) }
                        TextButton(onClick = { scope.launch { c.sources.deleteLocalSource(src.id) }; dlg = null }) { Text("删除", color = Bad) }
                    }
                }) { name, api ->
                scope.launch { c.sources.updateLocalSource(src.id, name, api, SourceRepository.detectType(api)) }
                dlg = null
            }
        }
        Dlg.Import -> {
            var url by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { dlg = null },
                title = { Text("订阅导入") },
                text = {
                    Column {
                        Text("支持格式：[{\"name\",\"api\"}] 或 {\"sources\":[…],\"parses\":[…]}（兼容 TVBox sites）",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(url, { url = it }, placeholder = { Text("https://…/sources.json") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && url.startsWith("http"), onClick = {
                        busy = true
                        scope.launch {
                            val msg = runCatching { c.sources.importSubscription(url.trim()) }
                                .fold({ (a, b) -> "导入 $a 个源、$b 个解析" }, { "导入失败：${it.message}" })
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            busy = false; dlg = null
                            c.health.checkAll(force = true)
                        }
                    }) { Text(if (busy) "导入中…" else "导入", color = Accent) }
                },
                dismissButton = { TextButton(onClick = { dlg = null }) { Text("取消") } },
            )
        }
        Dlg.AddParse -> ParseEditDialog("添加解析接口", "", "", "json", onDismiss = { dlg = null }) { n, u, t ->
            scope.launch { c.sources.addLocalParse(n, u, t) }
            dlg = null
        }
        is Dlg.EditParse -> {
            val p = d.item.parse
            if (p.builtIn) AlertDialog(
                onDismissRequest = { dlg = null }, title = { Text(p.name) },
                text = { Text("内置解析接口（只读）\n${p.url}") },
                confirmButton = { TextButton(onClick = { dlg = null }) { Text("关闭") } },
            ) else ParseEditDialog("编辑解析接口", p.name, p.url, p.type, onDismiss = { dlg = null },
                onDelete = { scope.launch { c.sources.deleteLocalParse(p.id) }; dlg = null }) { n, u, t ->
                scope.launch { c.sources.updateLocalParse(p.id, n, u, t) }
                dlg = null
            }
        }
        is Dlg.IntSlider -> {
            var v by remember(d) { mutableFloatStateOf(d.value.toFloat()) }
            AlertDialog(
                onDismissRequest = { dlg = null },
                title = { Text(d.title) },
                text = {
                    Column {
                        Text("${v.toInt()} ${d.unit}", color = Accent)
                        Slider(v, { v = it }, valueRange = d.range.first.toFloat()..d.range.last.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                    }
                },
                confirmButton = { TextButton(onClick = { d.onSave(v.toInt()); dlg = null }) { Text("保存", color = Accent) } },
                dismissButton = { TextButton(onClick = { dlg = null }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun SourceEditDialog(
    title: String, name0: String, api0: String,
    onDismiss: () -> Unit,
    testUrl: suspend (String) -> Long,
    extra: (@Composable () -> Unit)? = null,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var api by remember { mutableStateOf(api0) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("名称（可留空）") }, singleLine = true)
                OutlinedTextField(api, { api = it }, label = { Text("接口地址") },
                    placeholder = { Text("https://your-api.example/api.php/provide/vod") }, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = api.startsWith("http"), onClick = {
                        result = "测试中…"
                        scope.launch {
                            val ms = testUrl(api.trim())
                            result = if (ms >= 0) "✅ 成功 · ${ms}ms · ${if (SourceRepository.detectType(api) == "xml") "XML" else "JSON"}"
                            else "❌ 失败：无法获取分类或列表，请检查地址"
                        }
                    }) { Text("测试", color = Accent) }
                    result?.let { Text(it, fontSize = 12.sp) }
                }
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(enabled = api.startsWith("http"), onClick = { onSave(name.trim(), api.trim()) }) { Text("保存", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ParseEditDialog(
    title: String, name0: String, url0: String, type0: String,
    onDismiss: () -> Unit, onDelete: (() -> Unit)? = null,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var url by remember { mutableStateOf(url0) }
    var type by remember { mutableStateOf(type0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("名称（可留空）") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("解析地址前缀") },
                    placeholder = { Text("https://your-parse.example/?url=") }, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("JSON 接口", type == "json") { type = "json" }
                    Chip("网页嗅探", type == "webview") { type = "webview" }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("删除此解析", color = Bad) }
            }
        },
        confirmButton = { TextButton(enabled = url.startsWith("http"), onClick = { onSave(name.trim(), url.trim(), type) }) { Text("保存", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}


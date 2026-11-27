package com.vplayer.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.vplayer.app.player.HoldSeek
import com.vplayer.app.player.TapZone
import com.vplayer.app.ui.category.Chip
import com.vplayer.app.ui.components.Group
import com.vplayer.app.ui.components.OptionRow
import com.vplayer.app.ui.components.SwitchRow
import com.vplayer.app.ui.components.formatTime
import com.vplayer.app.ui.theme.Accent
import kotlinx.coroutines.delay
import kotlin.math.abs

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
private val RATIOS = listOf("适应", "填充", "拉伸", "16:9", "4:3")

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, activity: PlayerActivity, inPip: Boolean) {
    val st by vm.state.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val window = activity.window

    // 横屏沉浸式
    DisposableEffect(landscape) {
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    BackHandler(enabled = landscape) { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }

    if (inPip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { VideoSurface(vm, st.resizeMode, Modifier.fillMaxSize()) }
        return
    }

    Column(Modifier.fillMaxSize().background(if (landscape) Color.Black else MaterialTheme.colorScheme.background)) {
        Box(
            (if (landscape) Modifier.fillMaxSize() else Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16 / 9f))
                .background(Color.Black)
        ) {
            VideoSurface(vm, st.resizeMode, Modifier.fillMaxSize())
            PlayerOverlay(vm, st, activity, landscape)
        }
        if (!landscape) InfoPanel(vm, st)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(vm: PlayerViewModel, resizeMode: Int, modifier: Modifier) {
    val ratio: Float? = when (resizeMode) { 3 -> 16 / 9f; 4 -> 4 / 3f; else -> null }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    player = vm.player
                }
            },
            update = { v ->
                v.player = vm.player
                v.resizeMode = when (resizeMode) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2, 3, 4 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SourceLockedOrientationActivity")
@Composable
private fun PlayerOverlay(vm: PlayerViewModel, st: PlayerUiState, activity: PlayerActivity, landscape: Boolean) {
    val player = vm.player
    var showControls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var tapHint by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val haptic = LocalHapticFeedback.current
    var interaction by remember { mutableLongStateOf(0L) }
    val audio = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            buffered = player.bufferedPosition
            playing = player.isPlaying
            delay(300)
        }
    }
    // 4 秒无操作自动隐藏
    LaunchedEffect(showControls, interaction, playing) {
        if (showControls && playing) { delay(4000); showControls = false }
    }
    LaunchedEffect(st.showResume) { if (st.showResume) { delay(6000); vm.hideResume() } }
    LaunchedEffect(tapHint) { if (tapHint != null) { delay(700); tapHint = null } }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { showControls = !showControls; interaction = System.currentTimeMillis() },
                    onDoubleTap = { o ->
                        if (!locked) when (TapZone.of(o.x, size.width.toFloat())) {
                            TapZone.LEFT -> { vm.seekBy(-10_000); tapHint = "« 10 秒" to System.currentTimeMillis() }
                            TapZone.RIGHT -> { vm.seekBy(10_000); tapHint = "10 秒 »" to System.currentTimeMillis() }
                            TapZone.CENTER -> if (player.isPlaying) player.pause() else player.play()
                        }
                    },
                    onLongPress = { o ->
                        if (!locked) {
                            val rewind = HoldSeek.isRewind(o.x, size.width.toFloat())
                            // 快进只在播放中生效；快退暂停时也可以用
                            if (rewind || player.isPlaying) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.startHold(rewind)
                            }
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        vm.endHold()
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var mode = 0 // 1 进度 2 亮度 3 音量
                var totalX = 0f
                var totalY = 0f
                var startX = 0f
                var startPos = 0L
                var target = 0L
                var startBright = 0.5f
                var startVol = 0
                val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                detectDragGestures(
                    onDragStart = { o ->
                        mode = 0; totalX = 0f; totalY = 0f; startX = o.x
                        startPos = player.currentPosition
                        val b = activity.window.attributes.screenBrightness
                        startBright = if (b >= 0) b else runCatching {
                            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                        }.getOrDefault(0.5f)
                        startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    onDragEnd = {
                        if (mode == 1) player.seekTo(target)
                        mode = 0; gestureText = null
                    },
                    onDragCancel = { mode = 0; gestureText = null },
                    onDrag = { change, d ->
                        change.consume()
                        totalX += d.x; totalY += d.y
                        if (mode == 0 && (abs(totalX) > 16 || abs(totalY) > 16)) {
                            mode = if (abs(totalX) > abs(totalY)) 1 else if (startX < size.width / 2) 2 else 3
                        }
                        when (mode) {
                            1 -> {
                                val dur = player.duration.coerceAtLeast(0)
                                target = (startPos + (totalX / size.width * 120_000).toLong()).coerceIn(0, dur)
                                gestureText = "${formatTime(target)} / ${formatTime(dur)}"
                            }
                            2 -> {
                                val v = (startBright - totalY / size.height).coerceIn(0.01f, 1f)
                                val attrs = activity.window.attributes
                                attrs.screenBrightness = v
                                activity.window.attributes = attrs
                                gestureText = "亮度 ${(v * 100).toInt()}%"
                            }
                            3 -> {
                                val v = (startVol - totalY / size.height * maxVol).toInt().coerceIn(0, maxVol)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                gestureText = "音量 ${v * 100 / maxVol.coerceAtLeast(1)}%"
                            }
                        }
                    },
                )
            }
    ) {
        if (st.loading && st.error == null) CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center).size(40.dp), strokeWidth = 3.dp)
        st.error?.let { err ->
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, color = Color.White, fontSize = 13.sp)
                TextButton(onClick = { vm.retry() }) { Text("重试", color = Accent) }
            }
        }
        gestureText?.let {
            Text(it, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        st.holdLabel?.let { label ->
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp).background(Color(0xCC000000), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp))
        }
        tapHint?.let { (t, _) ->
            Text(t, color = Color.White, fontSize = 15.sp,
                modifier = Modifier.align(if (t.startsWith("«")) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 56.dp).background(Color(0xAA000000), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        if (st.showResume) Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).padding(top = if (showControls) 36.dp else 0.dp)
                .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("已为你跳到上次位置 ${formatTime(st.resumeAt)} · ", color = Color.White, fontSize = 11.sp)
            Text("从头播放", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable { vm.restartFromBeginning() })
        }

        // 锁屏按钮
        if (showControls || locked) IconButton(
            onClick = { locked = !locked; showControls = true; interaction = System.currentTimeMillis() },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
        ) { Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen, "锁定", tint = Color.White) }

        if (showControls && !locked) {
            // 顶栏
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (landscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else activity.finish()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
                Text(
                    st.title + if (st.epName.isNotBlank()) " · ${st.epName}" else "", color = Color.White, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { activity.enterPip() }) { Icon(Icons.Default.PictureInPictureAlt, "画中画", tint = Color.White) }
            }
            // 中间播放按钮
            Box(
                Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Color(0x26FFFFFF))
                    .clickable { if (player.isPlaying) player.pause() else player.play(); interaction = System.currentTimeMillis() },
                contentAlignment = Alignment.Center,
            ) {
                if (!st.loading) Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放/暂停", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            // 底栏
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                val dur = duration.coerceAtLeast(1)
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    // 缓冲进度
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x30FFFFFF))) {
                        Box(Modifier.fillMaxWidth((buffered.toFloat() / dur).coerceIn(0f, 1f)).height(4.dp).background(Color(0x55FFFFFF)))
                    }
                    Slider(
                        value = dragging ?: (position.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { dragging = it; interaction = System.currentTimeMillis() },
                        onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatTime(dragging?.let { (it * dur).toLong() } ?: position)} / ${formatTime(duration)}",
                        color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f),
                    )
                    if (vm.hasNext()) IconButton(onClick = { vm.next() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.SkipNext, "下一集", tint = Color.White)
                    }
                    var speedMenu by remember { mutableStateOf(false) }
                    Text("${st.speed}x", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clickable { speedMenu = true }.padding(horizontal = 8.dp))
                    if (speedMenu) SpeedDialog(st.speed, { vm.setSpeed(it); speedMenu = false }) { speedMenu = false }
                    Text(RATIOS[st.resizeMode], color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clickable { vm.setResizeMode((st.resizeMode + 1) % RATIOS.size) }.padding(horizontal = 8.dp))
                    IconButton(onClick = {
                        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(if (landscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "全屏", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("倍速") },
        text = {
            Column {
                SPEEDS.forEach { s ->
                    Text("${s}x", color = if (s == current) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 10.dp))
                }
            }
        },
    )
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoPanel(vm: PlayerViewModel, st: PlayerUiState) {
    var bufferPct by remember { mutableFloatStateOf(0f) }
    var speedText by remember { mutableStateOf("") }
    var showSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            bufferPct = vm.player.bufferedPercentage.toFloat()
            val bps = vm.c.player.bandwidthMeter.bitrateEstimate / 8
            speedText = if (bps > 1024 * 1024) "%.1f MB/s".format(bps / 1048576f) else "${bps / 1024} KB/s"
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text(st.title + if (st.epName.isNotBlank()) " · ${st.epName}" else "", fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        val lineName = st.vod?.lines?.getOrNull(st.line)?.name ?: "本地"
        Text(
            "$lineName · ${if (st.isLocal) "离线播放" else if (st.isDirect) "直链" else "解析" + st.parseName.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()} · 已缓冲 ${bufferPct.toInt()}% · $speedText",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )

        val eps = st.vod?.lines?.getOrNull(st.line)?.episodes.orEmpty()
        if (eps.size > 1) {
            Text("选集", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                eps.forEachIndexed { i, e -> Chip(e.name, i == st.ep) { vm.playEpisode(st.line, i) } }
            }
        }
        val lines = st.vod?.lines.orEmpty()
        if (lines.size > 1) {
            Text("线路", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEachIndexed { i, l ->
                    Chip(l.name, i == st.line) { vm.playEpisode(i, st.ep.coerceAtMost(l.episodes.size - 1)) }
                }
            }
        }

        Text("播放器面板", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
        Group {
            OptionRow("倍速") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).forEach { s ->
                        Text(s.toString(), fontSize = 12.sp, color = if (s == st.speed) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (s == st.speed) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { vm.setSpeed(s) }.padding(2.dp))
                    }
                }
            }
            OptionRow("跳过片头 / 片尾", "仅对本剧生效", value = "${st.introSec}s / ${st.outroSec}s", onClick = { showSkip = true })
            SwitchRow("自动下一集", st.autoNext) { vm.setAutoNext(it) }
            OptionRow("画面比例") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RATIOS.forEachIndexed { i, r ->
                        Text(r, fontSize = 12.sp, color = if (i == st.resizeMode) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { vm.setResizeMode(i) }.padding(2.dp))
                    }
                }
            }
            OptionRow("长按快进") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2f, 3f).forEach { s ->
                        Text("${s.toInt()}x", fontSize = 12.sp, color = if (s == st.longPressSpeed) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { vm.setLongPressSpeed(s) }.padding(2.dp))
                    }
                }
            }
        }
        Text("手势：左侧亮度 · 右侧音量 · 横滑进度 · 双击左/右 ∓10 秒 · 双击中间暂停 · 长按右侧快进 / 左侧快退 · 画中画 · 锁屏",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        Spacer(Modifier.height(24.dp))
    }
    if (showSkip) SkipDialog(st.introSec, st.outroSec, onDismiss = { showSkip = false }) { a, b -> vm.setSkip(a, b); showSkip = false }
}

@Composable
private fun SkipDialog(intro: Int, outro: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var a by remember { mutableFloatStateOf(intro.toFloat()) }
    var b by remember { mutableFloatStateOf(outro.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳过片头 / 片尾") },
        text = {
            Column {
                Text("片头 ${a.toInt()} 秒")
                Slider(a, { a = it }, valueRange = 0f..300f, steps = 59, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text("片尾 ${b.toInt()} 秒")
                Slider(b, { b = it }, valueRange = 0f..300f, steps = 59, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(a.toInt(), b.toInt()) }) { Text("保存", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}


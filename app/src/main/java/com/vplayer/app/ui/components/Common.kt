package com.vplayer.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vplayer.app.App
import com.vplayer.app.AppContainer
import com.vplayer.app.data.model.Vod
import com.vplayer.app.ui.theme.Accent
import com.vplayer.app.ui.theme.posterBrush

@Composable
fun container(): AppContainer = (LocalContext.current.applicationContext as App).container

@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline build: (AppContainer) -> VM): VM {
    val c = container()
    return viewModel(key = key, factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = build(c) as T
    })
}

/** 缩略尺寸加载封面，带渐变占位 */
@Composable
fun Poster(url: String, seed: String, modifier: Modifier = Modifier, widthPx: Int = 270, heightPx: Int = 380) {
    val ctx = LocalContext.current
    Box(modifier.background(posterBrush(seed))) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).size(widthPx, heightPx).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 10.sp, color = Color.White, maxLines = 1,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VodCard(vod: Vod, onClick: () -> Unit, onLongClick: () -> Unit = {}, onVisible: () -> Unit = {}, badge: String? = null) {
    LaunchedEffect(vod.id) { onVisible() }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(MaterialTheme.shapes.small)
        ) {
            Poster(vod.pic, vod.name, Modifier.fillMaxSize())
            val b = badge ?: vod.remarks
            if (b.isNotBlank()) Badge(b, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            if (vod.score.isNotBlank()) Text(
                vod.score, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        Text(vod.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (action != null) Text(
            action, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.combinedClickableCompat(onAction),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

/** 设置分组卡片 */
@Composable
fun Group(title: String? = null, content: @Composable () -> Unit) {
    if (title != null) Text(
        title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) { content() }
}

@Composable
fun OptionRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.combinedClickableCompat(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.size(8.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            if (subtitle != null) Text(
                subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (value != null) Text(value + if (onClick != null) " ›" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) trailing()
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    OptionRow(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun EmptyBox(text: String, actionText: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        if (actionText != null) TextButton(onClick = onAction) { Text(actionText, color = Accent) }
    }
}

/** 延迟徽标：成功绿/黄，失败红 */
@Composable
fun LatencyBadge(ms: Long, tested: Boolean) {
    val (text, color) = when {
        !tested -> "未测" to MaterialTheme.colorScheme.onSurfaceVariant
        ms < 0 -> "失败" to Color(0xFFFF5A5A)
        ms < 500 -> "${ms}ms" to Color(0xFF3ECF8E)
        ms < 1500 -> "${ms}ms" to Color(0xFFE5B53A)
        else -> "${ms}ms" to Color(0xFFFF8A5A)
    }
    Text(
        text, fontSize = 11.sp, color = color,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toFloat())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toFloat())
    b >= 1L shl 10 -> "%d KB".format(b / 1024)
    else -> "$b B"
}

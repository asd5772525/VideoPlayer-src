package com.vplayer.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vplayer.app.data.model.UpdateInfo
import com.vplayer.app.ui.theme.Accent
import com.vplayer.app.ui.theme.HeroBrush
import kotlinx.coroutines.launch

/** 远程升级弹窗（与设计稿 ⑥ 一致），强制更新时不可关闭 */
@Composable
fun UpdateDialogHost(manager: UpdateManager, onMessage: (String) -> Unit) {
    val st by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when (val s = st) {
        UpdateState.Idle -> {}
        is UpdateState.Failed -> {
            if (s.info == null) LaunchedEffect(s) { onMessage(s.message); manager.dismiss() }
            else UpdateBox(s.info, null, "下载失败：${s.message}", onLater = { manager.dismiss() }) { scope.launch { manager.download(s.info) } }
        }
        is UpdateState.Available -> UpdateBox(s.info, null, null, onLater = { manager.dismiss() }) { scope.launch { manager.download(s.info) } }
        is UpdateState.Downloading -> UpdateBox(s.info, s.progress, "下载中 ${(s.progress * 100).toInt()}%，完成后自动安装", onLater = null) {}
        is UpdateState.ReadyToInstall -> UpdateBox(s.info, 1f, "下载完成", onLater = { manager.dismiss() }, primaryText = "安装") { manager.install(s.file) }
    }
}

@Composable
private fun UpdateBox(
    info: UpdateInfo, progress: Float?, status: String?,
    onLater: (() -> Unit)?, primaryText: String = "立即更新", onUpdate: () -> Unit,
) {
    val dismissible = !info.force && onLater != null
    Dialog(onDismissRequest = { if (dismissible) onLater?.invoke() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible)) {
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(22.dp)
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(HeroBrush), contentAlignment = Alignment.Center) {
                Text("影", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text("发现新版本", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("v${info.versionName}" + if (info.size.isNotBlank()) " · ${info.size}" else "", color = Accent, fontSize = 13.sp)
            Column(Modifier.padding(vertical = 12.dp)) {
                info.changelog.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp) }
            }
            if (progress != null) LinearProgressIndicator(
                progress = { progress }, color = Accent, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            )
            if (status != null) Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!info.force && onLater != null) OutlinedButton(onClick = onLater, modifier = Modifier.weight(1f)) { Text("稍后") }
                if (progress == null || progress >= 1f) Button(
                    onClick = onUpdate, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text(primaryText) }
            }
        }
    }
}

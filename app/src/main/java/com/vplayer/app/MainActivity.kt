package com.vplayer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vplayer.app.ui.nav.AppNav
import com.vplayer.app.ui.theme.Accent
import com.vplayer.app.ui.theme.AppTheme
import com.vplayer.app.update.UpdateDialogHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // 启动画面一直保持到设置（应用锁状态）读出来为止，避免开启应用锁时先闪一下首页
        val c = (application as App).container
        installSplashScreen().setKeepOnScreenCondition { !c.settings.loaded.value }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val s by c.settings.state.collectAsStateWithLifecycle()
            val loaded by c.settings.loaded.collectAsStateWithLifecycle()
            AppTheme(s.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var unlocked by rememberSaveable { mutableStateOf(false) }
                    if (!loaded) {
                        // 设置还没读出来：什么都不画（启动画面盖着），绝不先渲染首页
                        Box(Modifier.fillMaxSize())
                    } else if (s.appLock && s.appLockPin.length == 4 && !unlocked) {
                        LockScreen(s.appLockPin) { unlocked = true }
                    } else {
                        AppNav()
                        UpdateDialogHost(c.updates) { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            // 首帧之后的非关键任务：延迟执行，绝不阻塞启动
            LaunchedEffect(Unit) {
                delay(3000)
                lifecycleScope.launch(Dispatchers.IO) { runCatching { c.health.checkAll(force = false) } }
                delay(2000)
                lifecycleScope.launch(Dispatchers.IO) { runCatching { c.updates.check(manual = false) } }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            window.decorView.postDelayed({ notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, 4000)
        }
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(pin: String, onUnlock: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(input) {
        if (input.length == 4) {
            if (input == pin) onUnlock() else { error = true; delay(400); input = "" }
        }
    }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("请输入应用锁密码", fontSize = 16.sp)
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i ->
                Box(Modifier.size(14.dp).background(if (i < input.length) Accent else Color.Gray.copy(alpha = 0.4f), CircleShape))
            }
        }
        if (error) Text("密码错误", color = Color(0xFFFF5A5A), fontSize = 12.sp)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    TextButton(onClick = {
                        error = false
                        when (k) {
                            "⌫" -> input = input.dropLast(1)
                            "" -> {}
                            else -> if (input.length < 4) input += k
                        }
                    }, modifier = Modifier.size(72.dp)) { Text(k, fontSize = 24.sp) }
                }
            }
        }
    }
}

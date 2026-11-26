package com.vplayer.app.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vplayer.app.App
import com.vplayer.app.ui.theme.AppTheme

/**
 * 独立的播放 Activity：支持横屏全屏、画中画（PiP），配置变化不重建（manifest configChanges）。
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_VOD = "vod"
        private const val EXTRA_LINE = "line"
        private const val EXTRA_EP = "ep"
        private const val EXTRA_LOCAL = "local"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, sourceId: String, vodId: String, line: Int, ep: Int, localPath: String? = null, title: String? = null) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_SOURCE, sourceId).putExtra(EXTRA_VOD, vodId)
                    .putExtra(EXTRA_LINE, line).putExtra(EXTRA_EP, ep)
                    .putExtra(EXTRA_LOCAL, localPath).putExtra(EXTRA_TITLE, title)
            )
        }
    }

    lateinit var vm: PlayerViewModel
    var inPip by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = (application as App).container
        val i = intent
        @Suppress("UNCHECKED_CAST")
        vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                c,
                i.getStringExtra(EXTRA_SOURCE) ?: "",
                i.getStringExtra(EXTRA_VOD) ?: "",
                i.getIntExtra(EXTRA_LINE, 0),
                i.getIntExtra(EXTRA_EP, 0),
                i.getStringExtra(EXTRA_LOCAL),
                i.getStringExtra(EXTRA_TITLE),
            ) as T
        })[PlayerViewModel::class.java]

        setContent {
            val settings by c.settings.state.collectAsStateWithLifecycle()
            AppTheme(settings.theme) {
                PlayerScreen(vm, this, inPip)
            }
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val size = vm.player.videoSize
            val ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
            val safe = if (ratio.toFloat() > 2.39f) Rational(239, 100) else if (ratio.toFloat() < 0.42f) Rational(42, 100) else ratio
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(safe).build()) }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onPause() {
        super.onPause()
        vm.saveProgress()
    }

    override fun onStop() {
        super.onStop()
        vm.saveProgress()
        val pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!pip) vm.player.pause()
    }
}

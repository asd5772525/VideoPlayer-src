package com.vplayer.app

import android.content.Context
import com.vplayer.app.data.cms.CmsRepository
import com.vplayer.app.data.cms.HealthChecker
import com.vplayer.app.data.cms.SourceRepository
import com.vplayer.app.data.config.AppConfig
import com.vplayer.app.data.config.ConfigLoader
import com.vplayer.app.data.db.AppDatabase
import com.vplayer.app.data.prefs.SettingsRepository
import com.vplayer.app.download.DownloadRepository
import com.vplayer.app.player.PlayerManager
import com.vplayer.app.player.UrlResolver
import com.vplayer.app.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 简单的手动依赖注入容器 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val config: AppConfig = ConfigLoader.load(context)
    val db: AppDatabase = AppDatabase.create(context)
    val settings = SettingsRepository(context, appScope, config.player)
    val sources = SourceRepository(config, db, settings, appScope)
    val cms = CmsRepository(db, appScope)
    val health = HealthChecker(db, sources, settings) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (cm == null) null
        else caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    val resolver = UrlResolver(context, sources, settings)
    val player = PlayerManager(context, settings)
    val downloads = DownloadRepository(context, db, settings)
    val updates = UpdateManager(context, settings, config)
}

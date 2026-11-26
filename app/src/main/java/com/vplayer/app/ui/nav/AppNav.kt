package com.vplayer.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vplayer.app.ui.category.CategoryScreen
import com.vplayer.app.ui.detail.DetailScreen
import com.vplayer.app.ui.downloads.DownloadsScreen
import com.vplayer.app.ui.home.HomeScreen
import com.vplayer.app.ui.me.FavoritesScreen
import com.vplayer.app.ui.me.HistoryScreen
import com.vplayer.app.ui.me.MeScreen
import com.vplayer.app.ui.player.PlayerActivity
import com.vplayer.app.ui.search.SearchScreen
import com.vplayer.app.ui.settings.SettingsScreen
import com.vplayer.app.ui.theme.Accent

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "首页", Icons.Default.Home),
    Tab("category", "分类", Icons.Default.Category),
    Tab("downloads", "下载", Icons.Default.Download),
    Tab("me", "我的", Icons.Default.Person),
)

fun NavHostController.openDetail(sourceId: String, vodId: String) =
    navigate("detail/${Uri.encode(sourceId)}/${Uri.encode(vodId)}")

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = TABS.any { it.route == route }
    val ctx = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("home") {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    HomeScreen(
                        onOpen = { s, v -> nav.openDetail(s.id, v.id) },
                        onSearch = { nav.navigate("search") },
                        onPlayHistory = { h -> PlayerActivity.start(ctx, h.sourceId, h.vodId, h.lineIndex, h.epIndex) },
                        onAllHistory = { nav.navigate("history") },
                        onOpenSettings = { nav.navigate("settings") },
                    )
                }
            }
            composable("category") {
                Box(Modifier.fillMaxSize().statusBarsPadding()) { CategoryScreen(onOpen = { s, v -> nav.openDetail(s.id, v.id) }) }
            }
            composable("downloads") { Box(Modifier.fillMaxSize().statusBarsPadding()) { DownloadsScreen() } }
            composable("me") {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    MeScreen(
                        onHistory = { nav.navigate("history") },
                        onFavorites = { nav.navigate("favorites") },
                        onSettings = { nav.navigate("settings") },
                    )
                }
            }
            composable("search") {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    SearchScreen(onBack = { nav.popBackStack() }, onOpen = { s, v -> nav.openDetail(s.id, v.id) })
                }
            }
            composable(
                "detail/{sid}/{vid}",
                arguments = listOf(navArgument("sid") { type = NavType.StringType }, navArgument("vid") { type = NavType.StringType }),
            ) { e ->
                DetailScreen(
                    sourceId = e.arguments?.getString("sid") ?: "",
                    vodId = e.arguments?.getString("vid") ?: "",
                    onBack = { nav.popBackStack() },
                )
            }
            composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
            composable("favorites") {
                FavoritesScreen(onBack = { nav.popBackStack() }, onOpen = { f -> nav.openDetail(f.sourceId, f.vodId) })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

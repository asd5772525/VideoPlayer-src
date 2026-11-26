package com.vplayer.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.vplayer.app.data.db.HistoryEntity
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import com.vplayer.app.ui.components.EmptyBox
import com.vplayer.app.ui.components.LatencyBadge
import com.vplayer.app.ui.components.LoadingBox
import com.vplayer.app.ui.components.Poster
import com.vplayer.app.ui.components.SectionHeader
import com.vplayer.app.ui.components.VodCard
import com.vplayer.app.ui.components.appViewModel
import com.vplayer.app.ui.components.formatTime
import com.vplayer.app.ui.theme.Accent
import com.vplayer.app.ui.theme.HeroBrush
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Source, Vod) -> Unit,
    onSearch: () -> Unit,
    onPlayHistory: (HistoryEntity) -> Unit,
    onAllHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val source by vm.current.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val history by vm.continueWatching.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val refreshing by vm.healthRunning.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val uri = LocalUriHandler.current

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 顶栏：Logo + 源切换
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                append("影"); withStyle(SpanStyle(color = Accent)) { append("视") }
            }, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.clickable { menu = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source?.name ?: "未配置源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    sources.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.source.name + if (item.source.builtIn) "" else "（本地）",
                                        color = if (item.source.id == source?.id) Accent else Color.Unspecified)
                                    Spacer(Modifier.width(8.dp))
                                    LatencyBadge(item.latencyMs, item.tested)
                                }
                            },
                            onClick = { menu = false; vm.selectSource(item.source.id); tab = 0 },
                        )
                    }
                    DropdownMenuItem(text = { Text("管理数据源…") }, onClick = { menu = false; onOpenSettings() })
                }
            }
        }
        // 搜索框
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).height(38.dp)
                .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onSearch).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("搜索片名、演员", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 分类 Tab
        val tabs = listOf("推荐") + cats.map { it.name }
        if (tab >= tabs.size) tab = 0
        ScrollableTabRow(
            selectedTabIndex = tab, edgePadding = 0.dp, containerColor = Color.Transparent,
            divider = {},
            indicator = { pos ->
                if (tab < pos.size) TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Accent, height = 3.dp)
            },
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = i == tab, onClick = { tab = i }, text = {
                    Text(t, fontSize = 14.sp, fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == tab) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
        }

        val s = source
        if (s == null) {
            EmptyBox("还没有可用的数据源\n可在 设置 中添加本地源，或由管理员在后台配置后发布新版本", "去设置", onOpenSettings)
            return@Column
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            if (tab == 0) {
                val latest = vm.pager(s, null).collectAsLazyPagingItems()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    notice?.let { n ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Accent.copy(alpha = 0.12f))
                                    .clickable(enabled = n.link.isNotBlank()) { runCatching { uri.openUri(n.link) } }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Campaign, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("${n.title}：${n.text}", fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.Close, "关闭", Modifier.size(16.dp).clickable { vm.dismissNotice(n) })
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            feed.banner.isNotEmpty() -> Banner(feed.banner) { onOpen(s, it) }
                            feed.loading -> LoadingBox()
                            feed.error != null -> EmptyBox("加载失败：${feed.error}", "重试") { vm.refresh() }
                            else -> Spacer(Modifier.height(1.dp))
                        }
                    }
                    if (history.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                SectionHeader("继续观看", "全部 ›", onAllHistory)
                                ContinueRow(history, onPlayHistory)
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("最新更新") }
                    pagingItems(latest, onOpen = { onOpen(s, it) }, onVisible = { vm.prefetch(s, it) })
                }
            } else {
                val cat = cats.getOrNull(tab - 1)
                if (cat != null) {
                    val items = vm.pager(s, cat.id).collectAsLazyPagingItems()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        pagingItems(items, onOpen = { onOpen(s, it) }, onVisible = { vm.prefetch(s, it) })
                    }
                }
            }
        }
    }
}

/** 网格中的分页条目 + 加载状态 */
fun LazyGridScope.pagingItems(items: LazyPagingItems<Vod>, onOpen: (Vod) -> Unit, onVisible: (Vod) -> Unit) {
    items(items.itemCount) { i ->
        val v = items[i]
        if (v != null) VodCard(v, onClick = { onOpen(v) }, onLongClick = { onVisible(v) }, onVisible = { onVisible(v) })
    }
    when (val st = items.loadState.append) {
        is LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox(Modifier.height(60.dp)) }
        is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyBox("加载失败：${st.error.message}", "重试") { items.retry() } }
        else -> {}
    }
    when (val st = items.loadState.refresh) {
        is LoadState.Loading -> if (items.itemCount == 0) item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox() }
        is LoadState.Error -> if (items.itemCount == 0) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyBox("加载失败：${st.error.message}", "重试") { items.retry() }
        }
        is LoadState.NotLoading -> if (items.itemCount == 0) item(span = { GridItemSpan(maxLineSpan) }) { EmptyBox("暂无内容") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Banner(list: List<Vod>, onClick: (Vod) -> Unit) {
    val state = rememberPagerState { list.size }
    LaunchedEffect(list.size) {
        while (list.size > 1) {
            delay(4000)
            state.animateScrollToPage((state.currentPage + 1) % list.size)
        }
    }
    Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp)).background(HeroBrush)) {
        HorizontalPager(state, Modifier.fillMaxSize()) { page ->
            val v = list[page]
            Box(Modifier.fillMaxSize().clickable { onClick(v) }) {
                Poster(v.pic, v.name, Modifier.fillMaxSize(), widthPx = 720, heightPx = 400)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(v.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    Text(listOf(v.remarks, v.score.takeIf { it.isNotBlank() }?.let { "$it 分" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(list.size) { i ->
                Box(Modifier.height(6.dp).width(if (i == state.currentPage) 16.dp else 6.dp).clip(CircleShape)
                    .background(if (i == state.currentPage) Color.White else Color.White.copy(alpha = 0.5f)))
            }
        }
    }
}

@Composable
private fun ContinueRow(list: List<HistoryEntity>, onClick: (HistoryEntity) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.vodKey }) { h ->
            Column(Modifier.width(110.dp).clickable { onClick(h) }) {
                Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp))) {
                    Poster(h.pic, h.name, Modifier.fillMaxSize(), widthPx = 300, heightPx = 190)
                    if (h.duration > 0) LinearProgressIndicator(
                        progress = { (h.position.toFloat() / h.duration).coerceIn(0f, 1f) },
                        color = Accent, trackColor = Color(0x552A2D36),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp).height(4.dp).clip(CircleShape),
                    )
                }
                Text(h.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    (if (h.epCount > 1) "看到 ${h.epName} " else "看到 ") + formatTime(h.position),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

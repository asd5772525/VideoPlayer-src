package com.vplayer.app.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import com.vplayer.app.ui.components.EmptyBox
import com.vplayer.app.ui.components.appViewModel
import com.vplayer.app.ui.home.HomeViewModel
import com.vplayer.app.ui.home.pagingItems
import com.vplayer.app.ui.theme.Accent

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp,
        color = if (selected) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier
            .then(if (selected) Modifier.border(1.dp, Accent, RoundedCornerShape(14.dp)) else Modifier)
            .background(if (selected) Accent.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
fun CategoryScreen(onOpen: (Source, Vod) -> Unit) {
    val vm = appViewModel(key = "category") { HomeViewModel(it) }
    val source by vm.current.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    var sel by rememberSaveable { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("分类", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
        val s = source ?: run { EmptyBox("还没有可用的数据源"); return@Column }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            item { Chip("全部", sel == -1) { sel = -1 } }
            itemsIndexed(cats) { i, c -> Chip(c.name, sel == i) { sel = i } }
        }
        val typeId = cats.getOrNull(sel)?.id
        val items = vm.pager(s, typeId).collectAsLazyPagingItems()
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            pagingItems(items, onOpen = { onOpen(s, it) }, onVisible = { vm.prefetch(s, it) })
        }
    }
}


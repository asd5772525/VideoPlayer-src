package com.vplayer.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.vplayer.app.AppContainer
import com.vplayer.app.data.cms.vodPager
import com.vplayer.app.data.db.SearchHistoryEntity
import com.vplayer.app.data.model.Source
import com.vplayer.app.data.model.Vod
import com.vplayer.app.ui.category.Chip
import com.vplayer.app.ui.components.appViewModel
import com.vplayer.app.ui.home.pagingItems
import com.vplayer.app.ui.theme.Accent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(val c: AppContainer) : ViewModel() {
    val history = c.db.searchDao().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val current = c.sources.current
    private var lastKey: String? = null
    private var lastFlow: Flow<PagingData<Vod>>? = null

    fun results(source: Source, keyword: String): Flow<PagingData<Vod>> {
        val key = source.id + "|" + keyword
        if (key != lastKey || lastFlow == null) {
            lastKey = key
            lastFlow = vodPager(c.cms, source, null, keyword, c.appScope).cachedIn(viewModelScope)
            viewModelScope.launch { c.db.searchDao().put(SearchHistoryEntity(keyword, System.currentTimeMillis())) }
        }
        return lastFlow!!
    }

    fun clearHistory() = viewModelScope.launch { c.db.searchDao().clear() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (Source, Vod) -> Unit) {
    val vm = appViewModel { SearchViewModel(it) }
    val history by vm.history.collectAsStateWithLifecycle()
    val source by vm.current.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var keyword by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (keyword.isEmpty()) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("搜索片名、演员", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (input.isNotBlank()) keyword = input.trim() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            TextButton(onClick = { if (input.isNotBlank()) keyword = input.trim() }) { Text("搜索", color = Accent) }
        }
        val s = source
        if (keyword.isBlank() || s == null) {
            if (history.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearHistory() }) { Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.forEach { h -> Chip(h.keyword, false) { input = h.keyword; keyword = h.keyword } }
                }
            }
        } else {
            val items = vm.results(s, keyword).collectAsLazyPagingItems()
            Text("在「${s.name}」中搜索 “$keyword”", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                pagingItems(items, onOpen = { onOpen(s, it) }, onVisible = { vm.c.cms.prefetch(s, it.id) })
            }
        }
    }
}

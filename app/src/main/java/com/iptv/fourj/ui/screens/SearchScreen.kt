package com.iptv.fourj.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.iptv.fourj.data.api.StreamType
import com.iptv.fourj.data.model.LiveStream
import com.iptv.fourj.data.model.SeriesStream
import com.iptv.fourj.data.model.VodStream
import com.iptv.fourj.data.repository.IptvRepository
import com.iptv.fourj.ui.navigation.Routes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchTabContent(navController: NavHostController, repository: IptvRepository) {
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All", "Live", "Movies", "Series")

    var liveResults by remember { mutableStateOf<List<LiveStream>>(emptyList()) }
    var vodResults by remember { mutableStateOf<List<VodStream>>(emptyList()) }
    var seriesResults by remember { mutableStateOf<List<SeriesStream>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { searchFocus.requestFocus() }

    fun doSearch(q: String) {
        if (q.isBlank()) {
            liveResults = emptyList()
            vodResults = emptyList()
            seriesResults = emptyList()
            hasSearched = false
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(200) // Lower delay since it's local and extremely fast
            loading = true
            error = null
            hasSearched = true
            try {
                // These now search locally cached lists, making them instant and 100% correct
                val live = repository.searchLiveStreams(q)
                val vod = repository.searchVodStreams(q)
                val series = repository.searchSeriesStreams(q)
                liveResults = live
                vodResults = vod
                seriesResults = series
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Embedded Search Bar (No Back Button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    doSearch(it)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .focusRequester(searchFocus),
                placeholder = { Text("Search instantly across Live, Movies, & Series...", fontSize = 14.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }

        // Filter Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, title ->
                SearchTab(
                    title = title,
                    isSelected = selectedTab == index,
                    count = when (index) {
                        0 -> liveResults.size + vodResults.size + seriesResults.size
                        1 -> liveResults.size
                        2 -> vodResults.size
                        3 -> seriesResults.size
                        else -> 0
                    },
                    onClick = { selectedTab = index }
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))

        // Results Content Area
        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                }
            }
            !hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Type to search locally",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontSize = 15.sp
                        )
                    }
                }
            }
            else -> {
                val allEmpty = liveResults.isEmpty() && vodResults.isEmpty() && seriesResults.isEmpty()
                if (allEmpty) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(300.dp),
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selectedTab == 0 || selectedTab == 1) {
                            itemsIndexed(liveResults) { _, stream ->
                                SearchResultItem(
                                    title = stream.name,
                                    subtitle = stream.categoryId,
                                    type = "LIVE",
                                    onClick = {
                                        val url = repository.getStreamUrl(StreamType.LIVE, stream.streamId)
                                        navController.navigate(Routes.player(url, stream.name))
                                    }
                                )
                            }
                        }
                        if (selectedTab == 0 || selectedTab == 2) {
                            itemsIndexed(vodResults) { _, stream ->
                                SearchResultItem(
                                    title = stream.name,
                                    subtitle = stream.containerExtension.uppercase(),
                                    type = "MOVIE",
                                    onClick = {
                                        val ext = stream.containerExtension.ifBlank { "mp4" }
                                        val url = repository.getStreamUrl(StreamType.MOVIE, stream.streamId, ext)
                                        navController.navigate(Routes.player(url, stream.name))
                                    }
                                )
                            }
                        }
                        if (selectedTab == 0 || selectedTab == 3) {
                            itemsIndexed(seriesResults) { _, stream ->
                                SearchResultItem(
                                    title = stream.name,
                                    subtitle = stream.genre,
                                    type = "SERIES",
                                    onClick = {
                                        navController.navigate(Routes.seriesDetail(stream.seriesId))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTab(
    title: String,
    isSelected: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .height(44.dp)
            .width(120.dp)
            .onFocusChanged { focused = it.hasFocus }
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    focused -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "($count)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    title: String,
    subtitle: String,
    type: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { focused = it.hasFocus }
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = type,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

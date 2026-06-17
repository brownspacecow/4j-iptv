package com.iptv.fourj.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.iptv.fourj.data.repository.IptvRepository
import com.iptv.fourj.ui.navigation.Routes

@Composable
fun HomeScreen(navController: NavHostController, repository: IptvRepository) {
    var selectedTab by remember { mutableIntStateOf(1) } // Default to "Live TV"
    val tabs = listOf("Search", "Live TV", "Movies", "Series")
    val icons = listOf(Icons.Default.Search, Icons.Default.LiveTv, Icons.Default.Movie, Icons.Default.Tv)

    LaunchedEffect(Unit) {
        repository.preloadContentCache()
    }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Sidebar
        Box(
            modifier = Modifier
                .width(232.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Logo
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Row {
                        Text(
                            text = "4",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "J",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main navigation items
                tabs.forEachIndexed { index, title ->
                    SidebarItem(
                        icon = icons[index],
                        label = title,
                        isSelected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Settings at bottom
                SidebarItem(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    isSelected = false,
                    onClick = { navController.navigate(Routes.PROVIDERS) }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline)
        )

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                0 -> SearchTabContent(navController, repository)
                1 -> LiveTabContent(navController, repository)
                2 -> MoviesTabContent(navController, repository)
                3 -> SeriesTabContent(navController, repository)
            }
        }
    }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

        Box(
            modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    focused -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    focused -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    focused -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        // Selected indicator bar
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(38.dp)
                    .align(Alignment.CenterStart)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun LiveTabContent(navController: NavHostController, repository: IptvRepository) {
    CategoryTabScreen(
        loader = { repository.getLiveCategories().map { it.categoryId to it.categoryName } },
        emptyMessage = "No live categories found.\nMake sure your provider credentials are valid and server is reachable.",
        onClick = { id, name -> navController.navigate(Routes.liveCategory(id, name)) }
    )
}

@Composable
private fun MoviesTabContent(navController: NavHostController, repository: IptvRepository) {
    CategoryTabScreen(
        loader = { repository.getVodCategories().map { it.categoryId to it.categoryName } },
        emptyMessage = "No movie categories found.\nMake sure your provider credentials are valid and server is reachable.",
        onClick = { id, name -> navController.navigate(Routes.moviesCategory(id, name)) }
    )
}

@Composable
private fun SeriesTabContent(navController: NavHostController, repository: IptvRepository) {
    CategoryTabScreen(
        loader = { repository.getSeriesCategories().map { it.categoryId to it.categoryName } },
        emptyMessage = "No series categories found.\nMake sure your provider credentials are valid and server is reachable.",
        onClick = { id, name -> navController.navigate(Routes.seriesCategory(id, name)) }
    )
}

@Composable
private fun CategoryTabScreen(
    loader: suspend () -> List<Pair<String, String>>,
    emptyMessage: String,
    onClick: (String, String) -> Unit
) {
    var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { loader() }
            .onSuccess { categories = it; loading = false }
            .onFailure { error = it.message ?: it.toString(); loading = false }
    }

    when {
        loading -> LoadingBox()
        error != null -> ErrorBox(error!!)
        categories.isEmpty() -> EmptyBox(emptyMessage)
        else -> CategoryList(categories = categories, onClick = onClick)
    }
}

@Composable
private fun CategoryList(
    categories: List<Pair<String, String>>,
    onClick: (String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = "Categories",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        itemsIndexed(categories) { index, (id, name) ->
            CategoryListItem(
                name = name,
                index = index,
                onClick = { onClick(id, name) }
            )
        }
    }
}

@Composable
private fun CategoryListItem(
    name: String,
    index: Int,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.17f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${index + 1}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(30.dp)
            )
            Text(
                text = name,
                fontSize = 14.sp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorBox(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Check your provider settings or try re-adding your provider.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyBox(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

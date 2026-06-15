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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.iptv.fourj.data.model.LiveCategory
import com.iptv.fourj.data.model.VodCategory
import com.iptv.fourj.data.model.SeriesCategory
import com.iptv.fourj.data.repository.IptvRepository
import com.iptv.fourj.ui.navigation.Routes

@Composable
fun HomeScreen(navController: NavHostController, repository: IptvRepository) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Live TV", "Movies", "Series")
    val icons = listOf(Icons.Default.LiveTv, Icons.Default.Movie, Icons.Default.Tv)

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Sidebar
        Box(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Logo
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "4J-IPTV",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
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
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> LiveTabContent(navController, repository)
                1 -> MoviesTabContent(navController, repository)
                2 -> SeriesTabContent(navController, repository)
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
            .height(56.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    focused -> MaterialTheme.colorScheme.surfaceVariant
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
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                fontSize = 15.sp,
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
                    .height(32.dp)
                    .align(Alignment.CenterStart)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun LiveTabContent(navController: NavHostController, repository: IptvRepository) {
    var categories by remember { mutableStateOf<List<LiveCategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repository.getLiveCategories() }
            .onSuccess { categories = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    when {
        loading -> LoadingBox()
        error != null -> ErrorBox(error!!)
        categories.isEmpty() -> EmptyBox("No live categories")
        else -> CategoryList(
            categories = categories.map { it.categoryId to it.categoryName },
            onClick = { id, name -> navController.navigate(Routes.liveCategory(id, name)) }
        )
    }
}

@Composable
private fun MoviesTabContent(navController: NavHostController, repository: IptvRepository) {
    var categories by remember { mutableStateOf<List<VodCategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repository.getVodCategories() }
            .onSuccess { categories = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    when {
        loading -> LoadingBox()
        error != null -> ErrorBox(error!!)
        categories.isEmpty() -> EmptyBox("No movie categories")
        else -> CategoryList(
            categories = categories.map { it.categoryId to it.categoryName },
            onClick = { id, name -> navController.navigate(Routes.moviesCategory(id, name)) }
        )
    }
}

@Composable
private fun SeriesTabContent(navController: NavHostController, repository: IptvRepository) {
    var categories by remember { mutableStateOf<List<SeriesCategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repository.getSeriesCategories() }
            .onSuccess { categories = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    when {
        loading -> LoadingBox()
        error != null -> ErrorBox(error!!)
        categories.isEmpty() -> EmptyBox("No series categories")
        else -> CategoryList(
            categories = categories.map { it.categoryId to it.categoryName },
            onClick = { id, name -> navController.navigate(Routes.seriesCategory(id, name)) }
        )
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = "Categories",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
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
            .height(48.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else Color.Transparent
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
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            Text(
                text = name,
                fontSize = 15.sp,
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Error: $message", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EmptyBox(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
    }
}

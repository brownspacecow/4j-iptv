package com.fourj.iptv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fourj.iptv.domain.model.Movie
import com.fourj.iptv.domain.model.Series
import com.fourj.iptv.ui.theme.LocalUiScale

/**
 * Poster browsing for films and series.
 *
 * A grid rather than the list live TV uses, because on-demand content is chosen by artwork and
 * title the way it is in every streaming app, and because a category can hold thousands of items
 * that a viewer scans rather than reads.
 */
@Composable
fun VodBrowseScreen(
    state: VodUiState,
    section: VodSection,
    library: LibraryState,
    onCategoryChange: (String) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiScale = LocalUiScale.current

    Surface(
        modifier = modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // No Movies/Series switcher in here on purpose: the app's top bar already carries both,
            // and a second control for the same choice is a trap - two things to keep in step, and
            // the viewer has to work out which one is the real tab.
            if (library.continueWatching.isNotEmpty()) {
                ContinueWatchingRow(
                    items = library.continueWatching,
                    onClick = { progress ->
                        // Resume is dispatched by the shell, which owns the player.
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = uiScale.horizontalMarginDp.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(count = state.categories.size, key = { state.categories[it].id }) { index ->
                    val category = state.categories[index]
                    Button(
                        onClick = { onCategoryChange(category.id) },
                        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f),
                    ) {
                        Text(category.name, maxLines = 1)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Deliberately not a shared `val items = if (...) movies else series`: that widens the
            // type to List<Any> and loses the element type inside both grid branches.
            val showingMovies = section == VodSection.MOVIES
            val isEmpty = if (showingMovies) state.visibleMovies.isEmpty() else state.visibleSeries.isEmpty()

            when {
                state.error != null -> Message(state.error, MaterialTheme.colorScheme.error)
                state.isLoadingItems && isEmpty -> Message("Loading…")
                isEmpty -> Message("Nothing here.")

                showingMovies -> LazyVerticalGrid(
                    columns = GridCells.Fixed(POSTERS_PER_ROW),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = uiScale.horizontalMarginDp.dp,
                        end = uiScale.horizontalMarginDp.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.visibleMovies, key = { it.id }) { movie ->
                        MovieCard(movie = movie, onClick = { onMovieClick(movie) })
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(POSTERS_PER_ROW),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = uiScale.horizontalMarginDp.dp,
                        end = uiScale.horizontalMarginDp.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.visibleSeries, key = { it.id }) { series ->
                        SeriesCard(series = series, onClick = { onSeriesClick(series) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieCard(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(POSTER_ASPECT),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Poster(movie.posterUrl, Modifier.fillMaxSize())
            Text(
                text = movie.name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun SeriesCard(series: Series, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(POSTER_ASPECT),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Poster(series.posterUrl, Modifier.fillMaxSize())
            Text(
                text = series.name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun Poster(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("—", style = MaterialTheme.typography.titleMedium)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<com.fourj.iptv.domain.model.PlaybackProgress>,
    onClick: (com.fourj.iptv.domain.model.PlaybackProgress) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resumable = items.filter { it.isResumable }
    if (resumable.isEmpty()) return
    Column(modifier = modifier.padding(horizontal = LocalUiScale.current.horizontalMarginDp.dp)) {
        Text(
            text = "Continue watching",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(count = resumable.size, key = { resumable[it].contentId }) { index ->
                val progress = resumable[index]
                Card(
                    onClick = { onClick(progress) },
                    modifier = Modifier.width(220.dp),
                    scale = CardDefaults.scale(focusedScale = 1.05f),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = progress.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${(progress.fraction * 100).toInt()}% watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress.fraction)
                                    .height(3.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Message(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Posters are 2:3, the standard for film artwork. */
private const val POSTER_ASPECT = 2f / 3f

private const val POSTERS_PER_ROW = 6

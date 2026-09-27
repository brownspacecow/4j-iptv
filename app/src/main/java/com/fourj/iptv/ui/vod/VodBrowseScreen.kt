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
    onCategoryChange: (String) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onRefresh: () -> Unit,
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
            // No "continue watching" row here. It used to sit above the category chips on this
            // screen, which meant it appeared on both the films and the series tab - the same two or
            // three cards twice over, pushing the shelf you came to see further down the screen. It
            // belongs in one place, and Library is that place.

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

            // Cache age and a deliberate refresh, on the same row and directly under the category
            // chips so the D-pad reaches it: the chips are full width, and anything pushed to the
            // right edge below them is a long way from the focused element and gets stepped over.
            ShelfStatusRow(
                syncedAtMillis = state.categorySyncedAtMillis,
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
            )

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

/**
 * How old this shelf's cached copy is, and a button to replace it.
 *
 * **Why the age is shown rather than a timer doing the work.** The panel serves no `ETag`, no
 * `Last-Modified` and no "changed since" parameter - all checked against it - and it ignores `limit`
 * and `start` as well, so a shelf is megabytes and there is no cheaper way to find out whether it
 * changed. Any automatic schedule would therefore be a guess that costs a large download whether or
 * not anything actually changed.
 *
 * So the cache is trusted until someone says otherwise, and this row is how they say it. Saying how
 * old the copy is matters as much as offering the refresh: a shelf that quietly stopped updating
 * would otherwise be indistinguishable from one that is genuinely current, and the viewer's only
 * clue would be a film they expected to be missing.
 */
@Composable
private fun ShelfStatusRow(
    syncedAtMillis: Long?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalUiScale.current.horizontalMarginDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = when {
                isRefreshing -> "Refreshing from your provider…"
                syncedAtMillis == null -> "Not downloaded yet"
                else -> "Cached copy from ${describeCacheAge(syncedAtMillis)}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!isRefreshing) {
            Button(
                onClick = onRefresh,
                scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f),
            ) {
                Text("Refresh")
            }
        }
    }
}

/**
 * How long ago something happened, in words a person would use.
 *
 * Deliberately vague past a week. "23 days ago" is precise and useless - at that point the useful
 * statement is simply that it is old, and a precise figure implies a freshness the app cannot
 * actually vouch for.
 */
internal fun describeCacheAge(syncedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val elapsedMinutes = (nowMillis - syncedAtMillis) / 60_000
    return when {
        elapsedMinutes < 1 -> "moments ago"
        elapsedMinutes < 60 -> "$elapsedMinutes minute${plural(elapsedMinutes)} ago"
        elapsedMinutes < 60 * 24 -> {
            val hours = elapsedMinutes / 60
            "$hours hour${plural(hours)} ago"
        }
        elapsedMinutes < 60 * 24 * 7 -> {
            val days = elapsedMinutes / (60 * 24)
            "$days day${plural(days)} ago"
        }
        else -> "over a week ago"
    }
}

private fun plural(value: Long): String = if (value == 1L) "" else "s"

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
internal fun Message(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Posters are 2:3, the standard for film artwork. */
private const val POSTER_ASPECT = 2f / 3f

private const val POSTERS_PER_ROW = 6

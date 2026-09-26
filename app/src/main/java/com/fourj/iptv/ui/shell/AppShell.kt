package com.fourj.iptv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.PlaybackProgress
import com.fourj.iptv.domain.model.ProviderProfile
import com.fourj.iptv.ui.epg.NowNextRow
import com.fourj.iptv.ui.live.LiveScreen
import com.fourj.iptv.ui.live.LiveViewModel
import com.fourj.iptv.ui.theme.LocalUiScale
import com.fourj.iptv.ui.vod.formatDuration
import com.fourj.iptv.ui.vod.DetailTarget
import com.fourj.iptv.ui.vod.SeriesDetailScreen
import com.fourj.iptv.ui.vod.VodBrowseScreen
import com.fourj.iptv.ui.vod.VodPlayerScreen
import com.fourj.iptv.ui.vod.VodSection
import com.fourj.iptv.ui.vod.VodViewModel

/** Top-level destinations. Plain state rather than a navigation graph: four screens, one stack. */
enum class TopLevel(val label: String) {
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    LIBRARY("Library"),
}

/** What the VOD player is currently showing. */
sealed interface VodPlayback {
    data class Film(
        val title: String,
        val url: String,
        val kind: ContentKind,
        val id: Int,
        val posterUrl: String?,
        val resumeSeconds: Long,
        val headers: Map<String, String>,
    ) : VodPlayback

    data class EpisodePlayback(
        val title: String,
        val subtitle: String?,
        val url: String,
        val seriesId: Int,
        val season: Int,
        val episodeNumber: Int,
        val episodeKey: String,
        val posterUrl: String?,
        val resumeSeconds: Long,
    ) : VodPlayback
}

@Composable
fun AppShell(
    container: AppContainer,
    profile: ProviderProfile,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf(TopLevel.LIVE) }
    var vodPlayback by remember { mutableStateOf<VodPlayback?>(null) }

    val liveViewModel: LiveViewModel = viewModel(
        key = "live-${profile.baseUrl}",
        factory = LiveViewModel.factory(container, profile),
    )
    val vodViewModel: VodViewModel = viewModel(
        key = "vod-${profile.baseUrl}",
        factory = VodViewModel.factory(container, profile),
    )

    val liveState by liveViewModel.state.collectAsStateWithLifecycle()
    val nowNext by liveViewModel.nowNext.collectAsStateWithLifecycle()
    val vodState by vodViewModel.state.collectAsStateWithLifecycle()
    val seriesDetail by vodViewModel.seriesDetail.collectAsStateWithLifecycle()
    val library by vodViewModel.library.collectAsStateWithLifecycle()

    val current = vodPlayback
    if (current != null) {
        when (current) {
            is VodPlayback.Film -> VodPlayerScreen(
                title = current.title,
                subtitle = null,
                streamUrl = current.url,
                kind = current.kind,
                contentId = current.id,
                posterUrl = current.posterUrl,
                resumePositionSeconds = current.resumeSeconds,
                requestHeaders = current.headers,
                onProgress = vodViewModel::saveProgress,
                onBack = {
                    vodPlayback = null
                    vodViewModel.closeDetail()
                },
            )

            is VodPlayback.EpisodePlayback -> VodPlayerScreen(
                title = current.title,
                subtitle = current.subtitle,
                streamUrl = current.url,
                kind = ContentKind.EPISODE,
                // Keyed on the episode row key so each episode resumes independently.
                contentId = current.episodeKey.hashCode(),
                posterUrl = current.posterUrl,
                resumePositionSeconds = current.resumeSeconds,
                requestHeaders = emptyMap(),
                onProgress = vodViewModel::saveProgress,
                onBack = {
                    vodPlayback = null
                    vodViewModel.closeDetail()
                },
            )
        }
        return
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                current = destination,
                onSelect = { destination = it },
                onSignOut = onSignOut,
            )

            when (destination) {
                TopLevel.LIVE -> LiveScreen(
                    container = container,
                    profile = profile,
                    viewModel = liveViewModel,
                    onSignOut = onSignOut,
                )

                TopLevel.MOVIES, TopLevel.SERIES -> {
                    val section = if (destination == TopLevel.MOVIES) VodSection.MOVIES else VodSection.SERIES
                    val detail = vodState.detail
                    if (detail is DetailTarget.SeriesTarget) {
                        val seriesState = seriesDetail
                        if (seriesState != null) {
                            SeriesDetailScreen(
                                detail = seriesState,
                                onSeasonChange = vodViewModel::selectSeason,
                                onEpisodeClick = { episode ->
                                    val url = vodViewModel.episodeUrl(episode) ?: return@SeriesDetailScreen
                                    vodPlayback = VodPlayback.EpisodePlayback(
                                        title = episode.title,
                                        subtitle = "${seriesState.series.name} Â· S${episode.seasonNumber}E${episode.episodeNumber}",
                                        url = url,
                                        seriesId = episode.seriesId,
                                        season = episode.seasonNumber,
                                        episodeNumber = episode.episodeNumber,
                                        episodeKey = episode.id,
                                        posterUrl = seriesState.series.posterUrl,
                                        resumeSeconds = 0,
                                    )
                                },
                                onBack = vodViewModel::closeDetail,
                            )
                        }
                    } else {
                        // The top bar is the only Movies/Series control, so choosing a destination
                        // is what switches the section. Driven here rather than from inside the
                        // browse screen so there is one source of truth for what is showing.
                        LaunchedEffect(section) { vodViewModel.browse(section) }
                        VodBrowseScreen(
                            state = vodState,
                            section = section,
                            library = library,
                            onCategoryChange = vodViewModel::selectCategory,
                            onMovieClick = { movie ->
                                vodViewModel.openMovie(movie)
                                val progress = library.progress(ContentKind.MOVIE, movie.id)
                                vodPlayback = VodPlayback.Film(
                                    title = movie.name,
                                    url = vodViewModel.movieUrl(movie),
                                    kind = ContentKind.MOVIE,
                                    id = movie.id,
                                    posterUrl = movie.posterUrl,
                                    resumeSeconds = progress?.positionSeconds ?: 0,
                                    headers = buildMap {
                                        movie.httpUserAgent?.let { put("User-Agent", it) }
                                        movie.httpReferrer?.let { put("Referer", it) }
                                    },
                                )
                            },
                            onSeriesClick = { vodViewModel.openSeries(it) },
                        )
                    }
                }

                TopLevel.LIBRARY -> LibraryScreen(
                    state = library,
                    onContinueClick = { progress -> resumeFrom(progress, vodViewModel, library) { vodPlayback = it } },
                    onClearProgress = { },
                )
            }
        }
    }
}

private fun resumeFrom(
    progress: PlaybackProgress,
    viewModel: VodViewModel,
    library: com.fourj.iptv.ui.vod.LibraryState,
    assign: (VodPlayback) -> Unit,
) {
    // Resuming needs the stream URL, which is only derivable from the catalogue entry, so a resume
    // from the library row opens the browse screen at that title rather than guessing a URL.
    assign(
        VodPlayback.Film(
            title = progress.title,
            url = "",
            kind = progress.kind,
            id = progress.contentId,
            posterUrl = progress.posterUrl,
            resumeSeconds = progress.positionSeconds,
            headers = emptyMap(),
        ),
    )
}

@Composable
private fun TopBar(
    current: TopLevel,
    onSelect: (TopLevel) -> Unit,
    onSignOut: () -> Unit,
) {
    val uiScale = LocalUiScale.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = uiScale.horizontalMarginDp.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "4J TV",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        TopLevel.entries.forEach { entry ->
            Button(
                onClick = { onSelect(entry) },
                scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f),
            ) {
                Text(entry.label)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LibraryScreen(
    state: com.fourj.iptv.ui.vod.LibraryState,
    onContinueClick: (PlaybackProgress) -> Unit,
    onClearProgress: () -> Unit,
) {
    val uiScale = LocalUiScale.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = uiScale.horizontalMarginDp.dp),
    ) {
        Text(
            text = "Continue watching",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        if (state.continueWatching.isEmpty()) {
            Text(
                text = "Nothing in progress.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(count = state.continueWatching.size, key = { index ->
                    // The key lambda receives the index, not the item. Kind is part of the key
                    // because ids overlap between films, episodes and channels.
                    val p = state.continueWatching[index]
                    "${p.kind}:${p.contentId}"
                }) { index ->
                    val progress = state.continueWatching[index]
                    Card(
                        onClick = { onContinueClick(progress) },
                        modifier = Modifier.width(230.dp),
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
                            progress.subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${formatDuration(progress.positionSeconds * 1000)} of " +
                                    formatDuration(progress.durationSeconds * 1000),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Favourites",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        if (state.favourites.isEmpty()) {
            Text(
                text = "No favourites yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(count = state.favourites.size, key = { index ->
                    state.favourites[index].contentKey
                }) { index ->
                    val favourite = state.favourites[index]
                    Card(
                        onClick = { },
                        modifier = Modifier.width(230.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            favourite.posterUrl?.let { poster ->
                                AsyncImage(
                                    model = poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.surface),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                text = favourite.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}



package com.fourj.iptv.ui.shell

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import com.fourj.iptv.ui.vod.Resumable
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
        val progressKey: String,
        val posterUrl: String?,
        val resumeSeconds: Long,
        val headers: Map<String, String>,
    ) : VodPlayback

    data class EpisodePlayback(
        val title: String,
        val subtitle: String?,
        val url: String,
        val progressKey: String,
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
    val scope = rememberCoroutineScope()
    val tabFocus = remember { FocusRequester() }
    val activity = LocalContext.current as? Activity

    /**
     * Whether focus has descended past the tab bar.
     *
     * Tracked rather than guessed, because the alternative - always pulling focus back on back -
     * makes back feel broken when the viewer is already on the tab bar trying to change tabs.
     */
    var contentHasFocus by remember { mutableStateOf(false) }

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

    /**
     * Back returns to the tab bar before it changes tabs, and only leaves from Live TV.
     *
     * Two separate problems, one fix. The tab bar is unreachable once focus descends into a tab's
     * content - a grid or a list swallows left and right, so there is no key that gets you back and
     * the app feels stuck. And because the tabs are peers rather than a stack, back from a
     * top-level tab should land on Live TV rather than dropping out of the application, which on a
     * television means losing your place for pressing back once too many.
     *
     * Disabled outright while a player is up. Leaving it enabled and relying on the player's own
     * handler being registered later is how back ended up closing the series detail as well as the
     * player: two handlers, one key press, and the wrong one won.
     */
    BackHandler(enabled = vodPlayback == null) {
        when {
            vodState.detail != null -> vodViewModel.closeDetail()
            contentHasFocus -> tabFocus.requestFocus()
            destination != TopLevel.LIVE -> destination = TopLevel.LIVE
            // Already on Live TV with focus on the tab bar: this is the bottom of the stack, so
            // let the system close the app.
            else -> {
                activity?.finish()
            }
        }
    }

    /**
     * Open a "continue watching" row.
     *
     * Resolved against the cache rather than guessed. A row whose film has been withdrawn, or whose
     * series has aged out of the cache, resolves to null and is left alone - much better than
     * opening a player on an empty url and reporting a decode error the viewer can do nothing
     * about.
     */
    suspend fun resume(progress: PlaybackProgress) {
        when (val resumable = vodViewModel.resolveForResume(progress)) {
            is Resumable.FilmItem -> vodPlayback = VodPlayback.Film(
                title = resumable.movie.name,
                url = resumable.url,
                kind = ContentKind.MOVIE,
                id = resumable.movie.id,
                progressKey = progress.contentKey,
                posterUrl = resumable.movie.posterUrl,
                resumeSeconds = resumable.resumeSeconds,
                headers = emptyMap(),
            )

            is Resumable.EpisodeItem -> vodPlayback = VodPlayback.EpisodePlayback(
                title = resumable.episode.title,
                subtitle = progress.subtitle,
                url = resumable.url,
                progressKey = progress.contentKey,
                posterUrl = progress.posterUrl,
                resumeSeconds = resumable.resumeSeconds,
            )

            null -> Unit
        }
    }

    val current = vodPlayback
    if (current != null) {
        when (current) {
            is VodPlayback.Film -> VodPlayerScreen(
                title = current.title,
                subtitle = null,
                streamUrl = current.url,
                kind = current.kind,
                progressKey = current.progressKey,
                posterUrl = current.posterUrl,
                resumePositionSeconds = current.resumeSeconds,
                requestHeaders = current.headers,
                isFavourite = library.isFavourite(current.kind, current.id),
                onToggleFavourite = {
                    vodViewModel.toggleFavourite(
                        kind = current.kind,
                        id = current.id,
                        name = current.title,
                        subtitle = null,
                        posterUrl = current.posterUrl,
                    )
                },
                onProgress = vodViewModel::saveProgress,
                onBack = {
                    // Only leave the player. Closing the detail here as well meant one back press
                    // from an episode threw away the series you were reading as well, landing on the
                    // browse screen instead of where you were.
                    vodPlayback = null
                },
            )

            is VodPlayback.EpisodePlayback -> VodPlayerScreen(
                title = current.title,
                subtitle = current.subtitle,
                streamUrl = current.url,
                kind = ContentKind.EPISODE,
                progressKey = current.progressKey,
                posterUrl = current.posterUrl,
                resumePositionSeconds = current.resumeSeconds,
                requestHeaders = emptyMap(),
                isFavourite = library.isFavouriteByKey(current.progressKey),
                onToggleFavourite = {
                    vodViewModel.toggleFavouriteByKey(
                        contentKey = current.progressKey,
                        name = current.title,
                        subtitle = current.subtitle,
                        posterUrl = current.posterUrl,
                    )
                },
                onProgress = vodViewModel::saveProgress,
                onBack = { vodPlayback = null },
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
                focusRequester = tabFocus,
                onSelect = { destination = it },
                onSignOut = onSignOut,
            )

            // Focus tracking is scoped to the content area, not the whole column: the column also
            // contains the tab bar, and counting the bar's own focus as "in the content" would make
            // back pull focus back onto the bar it is already on.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { contentHasFocus = it.isFocused },
            ) {
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
                                    val url = vodViewModel.episodeUrl(episode)
                                    if (url == null) {
                                        // A real panel can list an episode with no stream behind it -
                                        // the Sky at Night on the provider tested, where every
                                        // episode had an empty direct_source. Say so, rather than
                                        // swallowing the press and looking broken.
                                        vodViewModel.reportNotice(
                                            "\"${episode.title}\" has no stream on your provider.",
                                        )
                                    } else {
                                        vodViewModel.clearNotice()
                                        vodPlayback = VodPlayback.EpisodePlayback(
                                            title = episode.title,
                                            subtitle = "${seriesState.series.name} · " +
                                                "S${episode.seasonNumber}E${episode.episodeNumber}",
                                            url = url,
                                            progressKey = vodViewModel.progressKeyForEpisode(episode),
                                            posterUrl = seriesState.series.posterUrl,
                                            resumeSeconds = 0,
                                        )
                                    }
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
                            onResumeClick = { progress -> scope.launch { resume(progress) } },
                            onMovieClick = { movie ->
                                vodViewModel.openMovie(movie)
                                val progress = library.progress(ContentKind.MOVIE, movie.id)
                                vodPlayback = VodPlayback.Film(
                                    title = movie.name,
                                    url = vodViewModel.movieUrl(movie),
                                    kind = ContentKind.MOVIE,
                                    id = movie.id,
                                    progressKey = vodViewModel.progressKeyForMovie(movie.id),
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
                    onContinueClick = { progress -> scope.launch { resume(progress) } },
                )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    current: TopLevel,
    focusRequester: FocusRequester,
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
                // Only the active tab carries the requester, so back lands on the tab the viewer is
                // actually looking at rather than on whichever one happens to be first.
                modifier = if (entry == current) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
                scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f),
            ) {
                Text(entry.label)
            }
        }
        Spacer(Modifier.weight(1f))
        // Sign out lives in the top bar rather than on the Live TV screen. It used to sit in that
        // screen's own header row, and the D-pad could not reach it from anywhere - up from the
        // category chips skipped past the header and landed on the "On now" row instead. A control
        // you can only hit with a pointer is not a control on a television. It also belongs with
        // the rest of the app's navigation, and it has to work from every tab, not just Live TV.
        Button(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun LibraryScreen(
    state: com.fourj.iptv.ui.vod.LibraryState,
    onContinueClick: (PlaybackProgress) -> Unit,
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
                    // The stored content key, not kind plus numeric id. An episode has no numeric
                    // id, so that combination is "EPISODE:0" for every episode and the second one
                    // watched crashes the app on a duplicate lazy-list key.
                    state.continueWatching[index].contentKey
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



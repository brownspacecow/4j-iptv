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

import androidx.compose.foundation.layout.widthIn
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
import com.fourj.iptv.data.repository.PlaceState

import com.fourj.iptv.ui.epg.NowNextRow
import com.fourj.iptv.ui.live.LiveScreen
import com.fourj.iptv.ui.live.LiveViewModel
import com.fourj.iptv.ui.search.SearchScreen
import com.fourj.iptv.ui.search.SearchViewModel
import com.fourj.iptv.ui.search.SyncScreen
import com.fourj.iptv.ui.theme.LocalUiScale
import com.fourj.iptv.ui.vod.formatDuration
import com.fourj.iptv.domain.model.Favorite
import com.fourj.iptv.ui.vod.DetailTarget
import com.fourj.iptv.ui.vod.SeriesDetailScreen
import com.fourj.iptv.ui.vod.VodBrowseScreen
import com.fourj.iptv.ui.vod.VodPlayerScreen
import com.fourj.iptv.ui.vod.VodSection
import kotlinx.coroutines.launch
import com.fourj.iptv.ui.vod.FavoriteTarget
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

    /**
     * Search, and it opens first.
     *
     * It was an overlay rather than a fifth tab, which was the right call about the top bar but wrong
     * about the priority: making it something you had to navigate to meant every single search began
     * with walking the tab bar to reach it. On a ten-foot interface, with an on-screen keyboard that
     * is slow to begin with, spending the first two presses of every search on getting to the search
     * box was the wrong trade.
     *
     * So the app opens here, with the field already focused and the keyboard up. Reaching search
     * costs nothing, and the tabs are still there for when the viewer wants to browse instead - they
     * are simply no longer in the way of the thing people mostly want.
     */
    var searchOpen by remember { mutableStateOf(true) }

    /**
     * Sync is a screen of its own rather than a control buried in search.
     *
     * It is a long, deliberate download, and people want to watch it and be able to stop it - a
     * footnote inside a search box gives neither. Search stays the question you ask; syncing stays
     * the thing you start.
     */
    var syncOpen by remember { mutableStateOf(false) }
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

    /**
     * Whether the live player is on screen.
     *
     * Read rather than tracked, because the live player's state belongs to [LiveViewModel] and is
     * surfaced by LiveScreen. Search is now deliberately left open underneath a channel opened from
     * a search result, so two other things have to know this: the search screen must not be drawn on
     * top of the video, and its back handler must stand down. They have to ask the same question, or
     * back would close the search while the player was still up.
     */
    val livePlayerUp = liveState.playing != null

    /**
     * Put focus on the current tab when a browse screen appears, rather than letting it land
     * wherever Compose decides.
     *
     * This is a fix for putting Search first in the bar, and it is worth recording because the
     * symptom looked like nothing at all. Compose gives initial focus to the first focusable thing
     * in the tree, which used to be the Live TV tab - so arriving on a browse screen put focus on
     * the tab you were already on, and nothing had to be done about it. Search is now first, so
     * arriving there put focus on Search instead, and a viewer who pressed OK out of habit or
     * rolled their thumb was taken straight back to the search screen they had just closed.
     *
     * Keyed on the overlay flags rather than on [destination] deliberately: this should only fire
     * on the transition *into* the browse screens. Keying it on the tab would pull focus out of the
     * content every time someone chose a different one, and the content is where the D-pad belongs
     * once you are in it.
     */
    LaunchedEffect(searchOpen, syncOpen) {
        if (!searchOpen && !syncOpen) {
            // runCatching because the bar may not have composed its tabs yet, and a FocusRequester
            // that is not attached throws rather than quietly doing nothing.
            runCatching { tabFocus.requestFocus() }
        }
    }

    val vodState by vodViewModel.state.collectAsStateWithLifecycle()
    val seriesDetail by vodViewModel.seriesDetail.collectAsStateWithLifecycle()
    val library by vodViewModel.library.collectAsStateWithLifecycle()

    val searchViewModel: SearchViewModel = viewModel(
        key = "search-${profile.baseUrl}",
        factory = SearchViewModel.factory(container, profile),
    )
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()

    /**
     * The approximate city, and the one request that goes to a server the app was not configured
     * with. See [com.fourj.iptv.data.remote.PlaceLookup] for what that discloses.
     *
     * Kicked off here rather than in a composable so it happens once per process rather than once
     * per composition, and it returns immediately - a cached city is already in the flow, so the
     * screen has something to draw on its first frame and never waits on the network. Every launch does
     * send a request; see [PlaceRepository] for why that is the right trade here and the wrong one for
     * anything metered.
     */
    val placeRepository = container.placeRepository
    val placeState by placeRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { placeRepository.refresh() }

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
    BackHandler(enabled = vodPlayback == null && !searchOpen) {
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
     * Closing search with back rather than through the general handler.
     *
     * A separate handler, because the general one would send back to the tab bar instead - the
     * viewer who opened search to find one thing would have to press back twice to get out of it,
     * and the first press would appear to do nothing. Search is a layer over the tabs, so back
     * peels off the layer.
     */
    BackHandler(enabled = (searchOpen || syncOpen) && !livePlayerUp) {
        searchOpen = false
        syncOpen = false
        searchViewModel.clearQuery()
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

    /**
     * Search, unless a live channel opened from it is playing.
     *
     * The player takes priority, which is the whole reason search can stay open underneath it. Live
     * TV is watched full-screen the way a set-top box does it, and the video is the priority - so this
     * branch steps aside, the main branch below renders the player instead, and the viewer finds the
     * same results waiting when they press back.
     */
    /**
     * Open a favorites row.
     *
     * A film or an episode plays. A series opens its detail rather than playing, because a favorite
     * series is a favorite list of episodes and picking one would be a guess - the same reasoning
     * as a series arriving from search.
     *
     * Null when the thing is no longer available. Nothing happens in that case, deliberately: a
     * bookmark to something the provider has withdrawn should be a dead row the viewer can see and
     * remove, not a player that opens on an empty url and reports a decode error they can do nothing
     * about.
     */
    suspend fun openFavorite(favorite: Favorite) {
        when (val target = vodViewModel.resolveFavorite(favorite)) {
            is FavoriteTarget.FilmItem -> {
                destination = TopLevel.MOVIES
                vodPlayback = VodPlayback.Film(
                    title = target.movie.name,
                    url = target.url,
                    kind = ContentKind.MOVIE,
                    id = target.movie.id,
                    progressKey = vodViewModel.progressKeyForMovie(target.movie.id),
                    posterUrl = target.movie.posterUrl,
                    resumeSeconds = target.resumeSeconds,
                    headers = emptyMap(),
                )
            }

            is FavoriteTarget.EpisodeItem -> {
                destination = TopLevel.SERIES
                vodPlayback = VodPlayback.EpisodePlayback(
                    title = target.episode.title,
                    subtitle = favorite.subtitle,
                    url = target.url,
                    progressKey = favorite.contentKey,
                    posterUrl = favorite.posterUrl,
                    resumeSeconds = target.resumeSeconds,
                )
            }

            is FavoriteTarget.SeriesItem -> {
                destination = TopLevel.SERIES
                vodViewModel.revealCategory(target.series.categoryId)
                vodViewModel.openSeries(target.series)
            }

            null -> Unit
        }
    }

    if ((searchOpen || syncOpen) && !livePlayerUp) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    // No tab is current while searching. The old code passed LIVE here, which lit
                    // "Live TV" up behind a search screen and claimed the viewer was somewhere they
                    // were not.
                    current = null,
                    searchActive = true,
                    place = placeState,
                    focusRequester = tabFocus,
                    onSelect = {
                        searchOpen = false
                        syncOpen = false
                        destination = it
                    },
                    onSignOut = onSignOut,
                    onSearch = { searchOpen = true; syncOpen = false },
                    onSync = { syncOpen = true; searchOpen = false },
                )
                if (syncOpen) {
                    SyncScreen(
                        state = searchState,
                        onStart = searchViewModel::startIndexing,
                        onStop = searchViewModel::cancelIndexing,
                        onBack = { syncOpen = false },
                    )
                } else {
                SearchScreen(
                    state = searchState,
                    onQueryChange = searchViewModel::onQueryChange,
                    onClear = searchViewModel::clearQuery,
                    onStartIndexing = searchViewModel::startIndexing,
                    onCancelIndexing = searchViewModel::cancelIndexing,
                    onHitClick = { hit ->
                        scope.launch {
                            when (hit.kind) {
                                ContentKind.LIVE_CHANNEL -> {
                                    // Cached if the viewer has opened this category, synthesised from
                                    // the hit if not. The old code played only on a cache hit and did
                                    // nothing otherwise, so every live channel from a category nobody
                                    // had browsed - which is most of what search is for - was a button
                                    // that did nothing when pressed.
                                    val cached = liveViewModel.findChannel(hit.contentId)
                                    val channel = cached ?: liveViewModel.channelForSearchHit(
                                        streamId = hit.contentId,
                                        name = hit.name,
                                        categoryId = hit.categoryId,
                                        iconUrl = hit.iconUrl,
                                    )
                                    // Search is deliberately left open underneath, unlike the other
                                    // two kinds. Sampling live channels by zapping is how this gets
                                    // watched - try one, if it is not what you wanted, back out and
                                    // take the next result - and closing search meant every attempt
                                    // ended on the Live TV grid with the results gone, so a second
                                    // attempt cost a whole re-search. Leaving it open is what makes the
                                    // results survive the trip.
                                    destination = TopLevel.LIVE
                                    if (cached != null) {
                                        // Only worth doing when there is a grid to agree with. For a
                                        // synthesised channel this would pull down a whole live
                                        // category the viewer never asked for, which is the opposite
                                        // of what a search-first flow should do.
                                        liveViewModel.revealChannel(channel)
                                    }
                                    liveViewModel.play(channel)
                                }

                                ContentKind.MOVIE -> {
                                    // Cached if the shelf has been opened, synthesised from the hit if
                                    // not. The old code played only on a cache hit and did nothing
                                    // otherwise, so every film on a shelf nobody had browsed was a
                                    // result that silently refused to play.
                                    val movie = vodViewModel.findMovieById(hit.contentId)
                                        ?: vodViewModel.movieForSearchHit(
                                            streamId = hit.contentId,
                                            name = hit.name,
                                            categoryId = hit.categoryId,
                                            posterUrl = hit.posterUrl,
                                        )
                                    searchOpen = false
                                    destination = TopLevel.MOVIES
                                    vodViewModel.revealCategory(movie.categoryId)
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
                                }

                                else -> {
                                    // A series opens its detail rather than playing: an episode has
                                    // to be picked, and jumping straight into one would guess.
                                    val series = vodViewModel.findSeriesById(hit.contentId)
                                        ?: vodViewModel.seriesForSearchHit(
                                            seriesId = hit.contentId,
                                            name = hit.name,
                                            categoryId = hit.categoryId,
                                            posterUrl = hit.posterUrl,
                                        )
                                    searchOpen = false
                                    destination = TopLevel.SERIES
                                    vodViewModel.revealCategory(series.categoryId)
                                    vodViewModel.openSeries(series)
                                }
                            }
                        }
                    },
                )
                }
            }
        }
        return
    }

    val current = vodPlayback
    if (current != null) {
        when (current) {
            is VodPlayback.Film -> VodPlayerScreen(
                contentId = current.id,
                title = current.title,
                subtitle = null,
                streamUrl = current.url,
                kind = current.kind,
                progressKey = current.progressKey,
                posterUrl = current.posterUrl,
                resumePositionSeconds = current.resumeSeconds,
                requestHeaders = current.headers,
                isFavorite = library.isFavorite(current.kind, current.id),
                onToggleFavorite = {
                    vodViewModel.toggleFavorite(
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
                // An episode has no numeric id; its row key is the handle.
                contentId = 0,
                title = current.title,
                subtitle = current.subtitle,
                streamUrl = current.url,
                kind = ContentKind.EPISODE,
                progressKey = current.progressKey,
                posterUrl = current.posterUrl,
                resumePositionSeconds = current.resumeSeconds,
                requestHeaders = emptyMap(),
                isFavorite = library.isFavoriteByKey(current.progressKey),
                onToggleFavorite = {
                    vodViewModel.toggleFavoriteByKey(
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
            // No top bar over live video.
            //
            // It was drawn above the player, which contradicted LiveScreen's own comment about
            // playing taking over the whole screen, and it cost real estate: the video was squeezed
            // into whatever was left and letterboxed. It also put Search - and a Sign out path one
            // press further along - on screen during playback, where a stray press costs the viewer
            // whatever they were watching.
            if (!livePlayerUp) {
                TopBar(
                    current = destination,
                    searchActive = false,
                    place = placeState,
                    focusRequester = tabFocus,
                    onSelect = { destination = it },
                    onSignOut = onSignOut,
                    onSearch = { searchOpen = true },
                    onSync = { syncOpen = true },
                )
            }

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
                                isFavorite = library.isFavorite(
                                    ContentKind.SERIES,
                                    seriesState.series.id,
                                ),
                                onToggleFavorite = {
                                    vodViewModel.toggleFavorite(
                                        kind = ContentKind.SERIES,
                                        id = seriesState.series.id,
                                        name = seriesState.series.name,
                                        subtitle = null,
                                        posterUrl = seriesState.series.posterUrl,
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
                            onCategoryChange = vodViewModel::selectCategory,
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
                            onRefresh = {
                                vodState.selectedCategoryId?.let(vodViewModel::refreshCategory)
                            },
                        )
                    }
                }

                TopLevel.LIBRARY -> LibraryScreen(
                    state = library,
                    onContinueClick = { progress -> scope.launch { resume(progress) } },
                    onFavoriteClick = { favorite -> scope.launch { openFavorite(favorite) } },
                )
                }
            }
        }
    }
}

@Composable
/**
 * The app's one persistent row.
 *
 * The bar is the same on every screen, search included. It used to drop the tabs while search was
 * open, on the reasoning that a person typing a question is not asking where they are — which is
 * true and does not justify a different interface. It made search the one screen whose chrome did
 * not match, and it is the screen the app opens on, so it was the first thing anyone saw.
 *
 * Search is a peer of the tabs rather than a separate control, and it comes first: it is the primary
 * action and this is the first screen, so making it the fifth button in the row made the thing most
 * people came to do the last thing they could reach.
 *
 * The row is still built so that nothing on it can be reached by accident and regretted — Account
 * takes two presses, and the safe answer is the one holding focus. See the comment on the sign-out
 * strip below.
 */
private fun TopBar(
    current: TopLevel?,
    searchActive: Boolean,
    place: PlaceState,
    focusRequester: FocusRequester,
    onSelect: (TopLevel) -> Unit,
    onSignOut: () -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
) {
    val uiScale = LocalUiScale.current
    var confirmSignOut by remember { mutableStateOf(false) }

    // Signing out asks first, and the safe answer is the one holding focus.
    //
    // This is not fussiness. Signing yourself out on a television costs a five-minute recovery with
    // an on-screen keyboard, and the old layout made it a single press: Sign out was the last button
    // in a horizontal row, so any run of Right presses ended on it. That was not hypothetical - it
    // happened twice while testing this app, once of them losing a session mid-test.
    //
    // So the button says "Account" rather than lying about what it does, and confirming is a second
    // deliberate press. "Stay signed in" takes the focus, because on a television the option under the
    // thumb should be the one that cannot cause damage.
    //
    // Deliberately the same row rather than a floating dialog: androidx.tv.material3 1.0.0 ships no
    // dialog at all, and a separate window brings its own focus behavior that is awkward to verify
    // without a real television. A strip in place keeps the D-pad path identical to every other
    // control in the bar.

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
        // The city, immediately after the wordmark and as small as this type system goes.
        //
        // It used to sit on the search screen, which meant it was invisible everywhere else and cost
        // a whole row of vertical space on the one screen where the results need the room. Beside
        // the app's own name it reads as part of the chrome rather than as content, and labelSmall
        // is deliberately two steps below the wordmark so it cannot compete with it.
        place.place?.let { city ->
            Spacer(Modifier.width(10.dp))
            Text(
                text = city.shortLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Bounded so a long place name cannot push the tabs along the row. The bar is a
                // fixed set of controls and a long city is the only thing here that can grow.
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        Spacer(Modifier.width(10.dp))

        // Search first, before the tabs.
        //
        // It is the app's primary action and this is the first screen it opens on, so putting it
        // after four tabs made the thing most people came to do the fifth thing in the row. It is a
        // peer of the tabs rather than a separate control now, which is also why it is highlighted
        // while search is open rather than leaving "Live TV" lit up behind a search screen.
        Button(
            onClick = onSearch,
            scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f),
            colors = if (searchActive) {
                androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                androidx.tv.material3.ButtonDefaults.colors()
            },
        ) { Text("Search") }
        Spacer(Modifier.width(10.dp))

        TopLevel.entries.forEach { entry ->
            Button(
                onClick = { onSelect(entry) },
                // Tabs switch on OK, not on focus.
                //
                // This was changed to activate on focus and it had to be changed back, twice over.
                // Loading on focus is what a television viewer expects and it does feel better, but it
                // makes every stray focus event a context switch: any moment the content loses focus -
                // a shelf still loading, a list rebuilding, a recomposition - focus search lands on
                // whichever tab is nearest and the app silently jumps there. Observed on the real
                // account: pressing OK on a series category threw the viewer out to Live TV and lost
                // the shelf they had just chosen, because the chip lost focus during the load and the
                // focus search found the Live TV tab.
                //
                // Before this, the same event moved focus onto a tab and did nothing, which is
                // harmless. That asymmetry is the whole argument: an extra keypress is a small price,
                // and being teleported to another section mid-task is not a bug anyone should have to
                // argue about.
                // The tab bar is unreachable once focus descends into content, so the FocusRequester
                // goes on whichever tab is current - and on no tab at all while search is open, where
                // `current` is null and back has nothing to pull focus back to.
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
        Button(onClick = onSync) { Text("Sync") }
        Spacer(Modifier.width(10.dp))
        if (confirmSignOut) {
            val stayFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { stayFocus.requestFocus() } }
            Button(
                onClick = { confirmSignOut = false },
                modifier = Modifier.focusRequester(stayFocus),
            ) { Text("Stay signed in") }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onSignOut) { Text("Sign out") }
        } else {
            Button(onClick = { confirmSignOut = true }) { Text("Account") }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: com.fourj.iptv.ui.vod.LibraryState,
    onContinueClick: (PlaybackProgress) -> Unit,
    onFavoriteClick: (Favorite) -> Unit,
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
            text = "Favorites",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        if (state.favorites.isEmpty()) {
            Text(
                text = "No favorites yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(count = state.favorites.size, key = { index ->
                    state.favorites[index].contentKey
                }) { index ->
                    val favorite = state.favorites[index]
                    Card(
                        // This did nothing at all. A favorites row that cannot be opened is a
                        // bookmark to nowhere, and it read as broken rather than as unfinished: the
                        // card focused, scaled and looked exactly like every other playable card in
                        // the app, so pressing it doing nothing was indistinguishable from a bug.
                        onClick = { onFavoriteClick(favorite) },
                        modifier = Modifier.width(230.dp),
                        scale = CardDefaults.scale(focusedScale = 1.05f),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            favorite.posterUrl?.let { poster ->
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
                                text = favorite.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // The subtitle is what tells an episode apart from its own series.
                            //
                            // Both are favoritable, both carry the same poster, and an episode's
                            // name begins with the series name - so a card showing only the name
                            // truncates to the same string twice and the two rows are
                            // indistinguishable. The subtitle is the series, which is exactly the
                            // thing being left out.
                            favorite.subtitle?.let { subtitle ->
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}



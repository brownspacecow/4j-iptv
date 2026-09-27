package com.fourj.iptv.ui.vod

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fourj.iptv.data.remote.toUserMessage
import com.fourj.iptv.data.repository.SearchRepository
import com.fourj.iptv.data.repository.VodRepository
import com.fourj.iptv.data.repository.contentKey
import com.fourj.iptv.data.repository.episodeContentKey
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.Episode
import com.fourj.iptv.domain.model.Favourite
import com.fourj.iptv.domain.model.Movie
import com.fourj.iptv.domain.model.PlaybackProgress
import com.fourj.iptv.domain.model.ProviderProfile
import com.fourj.iptv.domain.model.Season
import com.fourj.iptv.domain.model.Series
import com.fourj.iptv.domain.model.VodCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VodUiState(
    val categories: List<VodCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val isLoadingCategories: Boolean = true,
    val isLoadingItems: Boolean = false,
    /** A deliberate refresh is running, as opposed to a first load. */
    val isRefreshing: Boolean = false,
    /**
     * When the current shelf was last fetched, or null if it never has been.
     *
     * Surfaced because the cache does not expire and the viewer is the one who decides when to
     * refresh. Showing the age is what makes an indefinitely-cached shelf honest rather than
     * suspicious - without it, a shelf that quietly stopped updating would be indistinguishable
     * from one that is genuinely up to date.
     */
    val categorySyncedAtMillis: Long? = null,
    val error: String? = null,
    val query: String = "",
    val detail: DetailTarget? = null,
    /** The section being browsed, so the screen can re-derive when it changes. */
    val section: VodSection = VodSection.MOVIES,
) {
    val visibleMovies: List<Movie>
        get() = filterByQuery(movies) { it.name }

    val visibleSeries: List<Series>
        get() = filterByQuery(series) { it.name }

    private inline fun <T> filterByQuery(items: List<T>, name: (T) -> String): List<T> {
        val needle = query.trim()
        return if (needle.isEmpty()) items else items.filter { name(it).contains(needle, ignoreCase = true) }
    }
}

sealed interface DetailTarget {
    data class MovieTarget(val movie: Movie) : DetailTarget
    data class SeriesTarget(val series: Series) : DetailTarget
}

data class SeriesDetailState(
    val series: Series,
    val seasons: List<Season> = emptyList(),
    val selectedSeasonNumber: Int? = null,
    val episodes: List<Episode> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /**
     * Why a tap did nothing.
     *
     * Worth having: an episode with no stream is a real thing on a real provider, and silently
     * ignoring the press is indistinguishable from a broken app.
     */
    val notice: String? = null,
)

data class LibraryState(
    val continueWatching: List<PlaybackProgress> = emptyList(),
    val favourites: List<Favourite> = emptyList(),
) {
    fun isFavourite(kind: ContentKind, id: Int): Boolean =
        favourites.any { it.kind == kind && it.contentId == id }

    /** By content key, for episodes, which have no numeric id to match on. */
    fun isFavouriteByKey(contentKey: String): Boolean =
        favourites.any { it.contentKey == contentKey }

    fun progress(kind: ContentKind, id: Int): PlaybackProgress? =
        continueWatching.firstOrNull { it.contentKey == contentKey(kind, id) }
}

/** Which catalogue the browse screen is showing; they are separate namespaces in the panel. */
enum class VodSection(val kind: ContentKind) {
    MOVIES(ContentKind.MOVIE),
    SERIES(ContentKind.SERIES),
}

/** A "continue watching" row resolved back into something the player can open. */
sealed interface Resumable {
    data class FilmItem(val movie: Movie, val url: String, val resumeSeconds: Long) : Resumable
    data class EpisodeItem(val episode: Episode, val url: String, val resumeSeconds: Long) : Resumable
}

// `flatMapLatest` is still marked experimental, so the catalogue load opts in rather than leaving
// the warning to be rediscovered on every build.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VodViewModel(
    private val repository: VodRepository,
    private val searchRepository: SearchRepository,
    val profile: ProviderProfile,
) : ViewModel() {

    private val _state = MutableStateFlow(VodUiState())
    val state: StateFlow<VodUiState> = _state.asStateFlow()

    private val _seriesDetail = MutableStateFlow<SeriesDetailState?>(null)
    val seriesDetail: StateFlow<SeriesDetailState?> = _seriesDetail.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    /**
     * Which section is being browsed.
     *
     * A flow in its own right rather than a plain field, because the category and content
     * collectors have to react to it and reading a mutable var from inside a collector is how
     * they end up disagreeing with what is on screen.
     */
    private val section = MutableStateFlow(VodSection.MOVIES)

    init {
        observeCategoryList()
        observeContents()
        observeLibrary()
        refreshCategories()
    }

    private fun refreshCategories() {
        viewModelScope.launch {
            val films = repository.refreshVodCategories()
            val shows = repository.refreshSeriesCategories()
            val failure = films.exceptionOrNull() ?: shows.exceptionOrNull()
            if (failure != null) {
                _state.update {
                    it.copy(isLoadingCategories = false, error = failure.toUserMessage())
                }
            }
        }
    }

    /**
     * Categories for the current section only.
     *
     * Films and series are separate namespaces and a real provider returns them in one combined
     * list, so the categories are filtered by the kind recorded when they were fetched. Showing
     * both would let the Movies tab select a series category and display nothing.
     */
    private fun observeCategoryList() {
        viewModelScope.launch {
            section
                .flatMapLatest { current -> repository.observeVodCategories(current.kind) }
                .collect { categories ->
                    _state.update { it.copy(categories = categories, isLoadingCategories = false) }
                    // Adopt the first category when the current one is not in this section's list,
                    // so switching tabs never lands on an empty shelf.
                    val selected = _state.value.selectedCategoryId
                    if (categories.none { it.id == selected }) {
                        categories.firstOrNull()?.let { first -> selectCategory(first.id) }
                    }
                }
        }
    }

    /**
     * Load the selected category's contents, cached-first.
     *
     * Keyed on the (kind, category) pair and nothing else. Keying on the whole state instead looks
     * equivalent but is not: loading writes to the database, the database flow emits, the emission
     * updates the state, and the state change restarts the load - which writes again. That is a
     * loop, and it hammers the provider with the same request dozens of times a second.
     */
    private fun observeContents() {
        viewModelScope.launch {
            _state
                .map { it.section.kind to it.selectedCategoryId }
                .distinctUntilChanged()
                .flatMapLatest { (kind, categoryId) ->
                    if (categoryId == null) {
                        flowOf(ContentLoad())
                    } else {
                        flow {
                            emit(ContentLoad(loading = true))
                            // Reads the cache first and only fetches if the shelf is not already
                            // recorded as synced, so switching back to a shelf is instant and free.
                            repository.ensureCategoryLoaded(categoryId, kind)
                            emit(
                                ContentLoad(
                                    kind = kind,
                                    syncedAtMillis = repository.categorySyncedAt(categoryId, kind),
                                ),
                            )
                            emitAll(
                                if (kind == ContentKind.MOVIE) {
                                    repository.observeMovies(categoryId)
                                        .map { ContentLoad(movies = it, kind = kind) }
                                } else {
                                    repository.observeSeries(categoryId)
                                        .map { ContentLoad(series = it, kind = kind) }
                                },
                            )
                        }
                    }
                }
                .collect { load ->
                    _state.update {
                        it.copy(
                            movies = load.movies,
                            series = load.series,
                            isLoadingItems = load.loading,
                            // Only overwritten when the load actually reported a timestamp. The
                            // rows arriving from the database afterwards carry none, and blanking
                            // the age on every emission would make the label flicker and then vanish.
                            categorySyncedAtMillis = load.syncedAtMillis
                                ?: it.categorySyncedAtMillis,
                            error = null,
                        )
                    }
                    // Feed the search index as a shelf arrives. Free coverage: the rows were just
                    // downloaded anyway, so this costs one write and makes the shelf searchable
                    // without the viewer having to index anything deliberately.
                    load.kind?.let { kind -> indexShelfForSearch(kind, load.movies, load.series) }
                }
        }
    }

    private fun observeLibrary() {
        viewModelScope.launch {
            repository.observeContinueWatching().collect { rows ->
                _library.update { it.copy(continueWatching = rows) }
            }
        }
        viewModelScope.launch {
            repository.observeFavourites().collect { rows ->
                _library.update { it.copy(favourites = rows) }
            }
        }
    }

    fun browse(next: VodSection) {
        if (section.value == next) return
        section.value = next
        _state.update {
            it.copy(
                section = next,
                selectedCategoryId = null,
                // Clear the other section's contents so the previous list is never shown under the
                // new tab while the new one loads.
                series = if (next == VodSection.SERIES) emptyList() else it.series,
                movies = if (next == VodSection.MOVIES) emptyList() else it.movies,
            )
        }
    }
    fun selectCategory(categoryId: String) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    /**
     * Re-fetch this shelf from the provider, ignoring the cache.
     *
     * The escape hatch that replaces automatic change detection. The panel serves no `ETag`, no
     * `Last-Modified` and no "changed since" parameter, so there is no way for the app to find out
     * what has changed without downloading the shelf and looking - and a shelf here is megabytes,
     * because the panel ignores `limit` and `start` too. Caching indefinitely and refreshing on
     * request is the only version of this that does not quietly spend the viewer's data on a guess.
     */
    fun refreshCategory(categoryId: String) {
        val kind = _state.value.section.kind
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            repository.refreshCategory(categoryId, kind)
                .onSuccess { loadCacheAge(categoryId, kind) }
                .onFailure { throwable ->
                    _state.update { it.copy(error = throwable.toUserMessage()) }
                }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    private fun loadCacheAge(categoryId: String, kind: ContentKind) {
        viewModelScope.launch {
            val syncedAt = repository.categorySyncedAt(categoryId, kind)
            _state.update { it.copy(categorySyncedAtMillis = syncedAt) }
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun openMovie(movie: Movie) = _state.update { it.copy(detail = DetailTarget.MovieTarget(movie)) }

    fun openSeries(series: Series) {
        _state.update { it.copy(detail = DetailTarget.SeriesTarget(series)) }
        _seriesDetail.value = SeriesDetailState(series = series, isLoading = true)
        viewModelScope.launch {
            repository.loadSeriesDetail(series.id)
                .onSuccess { seasons ->
                    val first = seasons.firstOrNull()?.number
                    _seriesDetail.update {
                        it?.copy(
                            seasons = seasons,
                            selectedSeasonNumber = first,
                            isLoading = false,
                        )
                    }
                    if (first != null) loadEpisodes(series.id, first)
                }
                .onFailure { failure ->
                    _seriesDetail.update { it?.copy(isLoading = false, error = failure.toUserMessage()) }
                }
        }
    }

    fun selectSeason(number: Int) {
        val seriesId = _seriesDetail.value?.series?.id ?: return
        _seriesDetail.update { it?.copy(selectedSeasonNumber = number, episodes = emptyList()) }
        viewModelScope.launch { loadEpisodes(seriesId, number) }
    }

    /**
     * Episodes are read from the cache, which [openSeries] has just filled.
     *
     * Reading from disk rather than holding a few hundred episodes in memory is deliberate: a long
     * running series is comfortably more than fits comfortably in a ViewModel field alongside
     * everything else.
     */
    private suspend fun loadEpisodes(seriesId: Int, seasonNumber: Int) {
        val episodes = repository.cachedEpisodes(seriesId, seasonNumber)
        _seriesDetail.update { it?.copy(episodes = episodes) }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
        _seriesDetail.value = null
    }

    /** Explain a tap that could not do anything, so it does not read as the app hanging. */
    fun reportNotice(message: String) {
        _seriesDetail.update { it?.copy(notice = message) }
    }

    fun clearNotice() {
        _seriesDetail.update { it?.copy(notice = null) }
    }

    fun movieUrl(movie: Movie): String = repository.movieStreamUrl(movie)

    /**
     * Add a freshly loaded shelf to the search index.
     *
     * Best-effort, and for the same reason as the live channels: the index is a convenience, and a
     * failure writing to it must not disturb browsing or playback.
     */
    private fun indexShelfForSearch(
        kind: ContentKind,
        movies: List<Movie>,
        series: List<Series>,
    ) {
        val categoryName = _state.value.categories
            .firstOrNull { it.id == _state.value.selectedCategoryId }
            ?.name
        viewModelScope.launch {
            runCatching {
                if (kind == ContentKind.MOVIE) {
                    searchRepository.indexMovies(movies, categoryName)
                } else {
                    searchRepository.indexSeries(series, categoryName)
                }
            }.onFailure { Log.w(TAG, "could not index $categoryName for search", it) }
        }
    }

    /**
     * Resolve a film by id, for search results.
     *
     * Null when the film is not cached. The search index stores only names and ids, so a hit can
     * name something whose details were never fetched - the caller has to handle that rather than
     * assume every hit is playable.
     */
    suspend fun findMovieById(movieId: Int): Movie? = repository.findMovie(movieId)

    /** As [findMovieById], for series. */
    suspend fun findSeriesById(seriesId: Int): Series? = repository.findSeries(seriesId)

    /**
     * Point the browse screen at a title's own category before opening it.
     *
     * As with live channels: search can surface something from a shelf that was never opened, and
     * the grid is driven by whichever category is selected. Opening a film while the grid showed a
     * different shelf leaves the viewer returned to somewhere they were not looking.
     */
    suspend fun revealCategory(categoryId: String?) {
        val id = categoryId ?: return
        _state.update { it.copy(selectedCategoryId = id) }
    }

    fun episodeUrl(episode: Episode): String? = repository.episodeStreamUrl(episode)

    /**
     * A film to play from a search hit, fetching the shelf first when the record is not cached.
     *
     * Films cannot be synthesised the way a live channel can. A live stream is reliably `.ts` on an
     * Xtream panel, so the stream id alone addresses it. A film is `.mp4`, `.mkv`, `.avi`, `.ts` or
     * a dozen other things and the search index does not record which - it holds names and ids only.
     *
     * Guessing produces a URL the panel cannot serve. Verified on this account: a search hit for
     * "Dreamgirls (2006)" was given a `.mp4` url, the click worked, the player opened, and then died
     * with "Atom size less than header length" - a Matroska file behind an mp4 url. That is worse
     * than useless, because it looks like the film is broken rather than like the app guessed.
     *
     * So when the film is not already cached, its category is fetched - one request, the same one
     * tapping the category chip would make - and the real record is used, with its true container
     * extension, plot, rating and panel headers. The synthesised film stays as a last resort so a hit
     * from a shelf the provider will not serve still opens something.
     */
    suspend fun movieForSearchHit(
        streamId: Int,
        name: String,
        categoryId: String?,
        posterUrl: String?,
    ): Movie {
        if (categoryId != null) {
            repository.ensureCategoryLoaded(categoryId, ContentKind.MOVIE)
            repository.findMovie(streamId)?.let { return it }
        }
        return Movie(
            id = streamId,
            name = name,
            categoryId = categoryId,
            posterUrl = posterUrl,
            backdropUrl = null,
            containerExtension = null,
            directSource = null,
            httpUserAgent = null,
            httpReferrer = null,
            rating = null,
            plot = null,
            durationSeconds = null,
        )
    }

    /**
     * A series to open from a search hit, whether or not its shelf has ever been opened.
     *
     * A series cannot be played directly - an episode has to be picked, and guessing one would be
     * wrong - so what a hit needs is enough to open the detail screen. Episodes are then fetched by
     * series id, but the series row itself still has to come from somewhere, and a synthesised one
     * would show a detail screen with no artwork or description and nothing to tell it apart from a
     * real one. One category request is cheap for that.
     */
    suspend fun seriesForSearchHit(
        seriesId: Int,
        name: String,
        categoryId: String?,
        posterUrl: String?,
    ): Series {
        if (categoryId != null) {
            repository.ensureCategoryLoaded(categoryId, ContentKind.SERIES)
            repository.findSeries(seriesId)?.let { return it }
        }
        return Series(
            id = seriesId,
            name = name,
            categoryId = categoryId,
            posterUrl = posterUrl,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            rating = null,
        )
    }

    /** The key a film or episode's resume position is stored under. */
    fun progressKeyForMovie(movieId: Int): String = contentKey(ContentKind.MOVIE, movieId)

    fun progressKeyForEpisode(episode: Episode): String = episodeContentKey(episode.id)

    /**
     * Turn a "continue watching" row back into something playable.
     *
     * Returns null when the thing is no longer available - a film removed at the provider, or an
     * episode whose series has fallen out of the cache - so the caller can drop the row instead of
     * opening a player with an empty url.
     */
    suspend fun resolveForResume(progress: PlaybackProgress): Resumable? {
        val episodeKey = progress.episodeRowKey
        if (episodeKey != null) {
            val episode = repository.findEpisode(episodeKey) ?: return null
            val url = repository.episodeStreamUrl(episode) ?: return null
            return Resumable.EpisodeItem(episode, url, progress.positionSeconds)
        }
        if (progress.kind != ContentKind.MOVIE) return null
        val movie = repository.findMovie(progress.contentId) ?: return null
        return Resumable.FilmItem(movie, repository.movieStreamUrl(movie), progress.positionSeconds)
    }

    fun toggleFavourite(kind: ContentKind, id: Int, name: String, subtitle: String?, posterUrl: String?) {
        toggleFavouriteByKey(contentKey(kind, id), name, subtitle, posterUrl, kind = kind, contentId = id)
    }

    /**
     * Toggle by content key.
     *
     * Keyed rather than by a numeric id because an episode has none; [contentId] is only carried for
     * the display and is meaningless for an episode.
     */
    fun toggleFavouriteByKey(
        contentKey: String,
        name: String,
        subtitle: String?,
        posterUrl: String?,
        kind: ContentKind? = null,
        contentId: Int = 0,
    ) {
        viewModelScope.launch {
            val existing = repository.favouriteFor(contentKey)
            val derivedKind = kind ?: existing?.kind ?: kindFromKey(contentKey)
            if (existing != null) {
                repository.removeFavourite(contentKey)
            } else {
                repository.addFavourite(
                    Favourite(
                        contentKey = contentKey,
                        kind = derivedKind,
                        contentId = contentId,
                        name = name,
                        subtitle = subtitle,
                        posterUrl = posterUrl,
                        addedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** Read the kind back out of a key of the form `KIND:id`. */
    private fun kindFromKey(key: String): ContentKind =
        key.substringBefore(':').let {
            runCatching { ContentKind.valueOf(it) }.getOrDefault(ContentKind.MOVIE)
        }

    fun saveProgress(progress: PlaybackProgress) {
        viewModelScope.launch { repository.saveProgress(progress) }
    }

    companion object {
        private const val TAG = "4J"

        fun factory(container: AppContainer, profile: ProviderProfile) = viewModelFactory {
            initializer {
                VodViewModel(
                    repository = container.vodRepository(profile),
                    searchRepository = container.searchRepository(profile),
                    profile = profile,
                )
            }
        }
    }
}

/** Internal carrier for the catalogue stream, so loading and content land in one place. */
private data class ContentLoad(
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val loading: Boolean = false,
    /**
     * Which kind of shelf produced this, carried through the flow so the collector can index it.
     *
     * Null when nothing is selected. It has to travel with the rows rather than be read back out of
     * the state, because by the time the rows arrive the state may already have moved on to another
     * section - and indexing a film shelf under the series section's name would put the wrong
     * category label on every one of those titles.
     */
    val kind: ContentKind? = null,
    /** When the shelf was fetched, carried on the first emission only. See [VodUiState]. */
    val syncedAtMillis: Long? = null,
)


package com.fourj.iptv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fourj.iptv.data.remote.toUserMessage
import com.fourj.iptv.data.repository.VodRepository
import com.fourj.iptv.data.repository.contentKey
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
)

data class LibraryState(
    val continueWatching: List<PlaybackProgress> = emptyList(),
    val favourites: List<Favourite> = emptyList(),
) {
    fun isFavourite(kind: ContentKind, id: Int): Boolean =
        favourites.any { it.kind == kind && it.contentId == id }

    fun progress(kind: ContentKind, id: Int): PlaybackProgress? =
        continueWatching.firstOrNull { it.kind == kind && it.contentId == id }
}

/** Which catalogue the browse screen is showing; they are separate namespaces in the panel. */
enum class VodSection(val kind: ContentKind) {
    MOVIES(ContentKind.MOVIE),
    SERIES(ContentKind.SERIES),
}

class VodViewModel(
    private val repository: VodRepository,
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
                            repository.ensureCategoryLoaded(categoryId, kind)
                            emitAll(
                                if (kind == ContentKind.MOVIE) {
                                    repository.observeMovies(categoryId).map { ContentLoad(movies = it) }
                                } else {
                                    repository.observeSeries(categoryId).map { ContentLoad(series = it) }
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
                            error = null,
                        )
                    }
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

    fun movieUrl(movie: Movie): String = repository.movieStreamUrl(movie)

    fun episodeUrl(episode: Episode): String? = repository.episodeStreamUrl(episode)

    fun toggleFavourite(kind: ContentKind, id: Int, name: String, subtitle: String?, posterUrl: String?) {
        viewModelScope.launch {
            repository.toggleFavourite(
                Favourite(
                    contentKey = contentKey(kind, id),
                    kind = kind,
                    contentId = id,
                    name = name,
                    subtitle = subtitle,
                    posterUrl = posterUrl,
                    addedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun saveProgress(progress: PlaybackProgress) {
        viewModelScope.launch { repository.saveProgress(progress) }
    }

    companion object {
        fun factory(container: AppContainer, profile: ProviderProfile) = viewModelFactory {
            initializer { VodViewModel(container.vodRepository(profile), profile) }
        }
    }
}

/** Internal carrier for the catalogue stream, so loading and content land in one place. */
private data class ContentLoad(
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val loading: Boolean = false,
)


package com.fourj.iptv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fourj.iptv.data.repository.IndexCoverage
import com.fourj.iptv.data.repository.IndexCounts
import com.fourj.iptv.data.repository.SearchHit
import com.fourj.iptv.data.remote.toUserMessage
import com.fourj.iptv.data.repository.SearchRepository
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.LiveCategory
import com.fourj.iptv.domain.model.ProviderProfile
import com.fourj.iptv.domain.model.VodShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,
    val coverage: IndexCoverage? = null,
    val isIndexing: Boolean = false,
    val indexedSoFar: Int = 0,
    val indexError: String? = null,
    /** Shelves that could not be read at all, from the run in progress or the last one. */
    val skippedShelves: Int = 0,
    /** Per-kind detail, for the sync screen. */
    val syncProgress: SyncProgress = SyncProgress(),
) {
    val live: List<SearchHit> get() = hits.filter { it.kind == ContentKind.LIVE_CHANNEL }
    val movies: List<SearchHit> get() = hits.filter { it.kind == ContentKind.MOVIE }
    val series: List<SearchHit> get() = hits.filter { it.kind == ContentKind.SERIES }

    val isEmptyResult: Boolean
        get() = query.isNotBlank() && hits.isEmpty() && !isSearching

    /**
     * Whether a miss here means "not on your provider" or "not indexed yet".
     *
     * Worth distinguishing on screen. This panel has no server-side search, so a search that finds
     * nothing while the index is still incomplete has not actually proved the title is absent -
     * and saying so would be a lie the viewer could not detect.
     */
    val missingBecauseIncomplete: Boolean
        get() = isEmptyResult && coverage?.isComplete == false
}

/**
 * How far a sync has got, per kind.
 *
 * Split by kind because they finish at very different rates: live channels are a dozen requests and
 * quick, while films and series are the long part. One combined number would hide which half is
 * still outstanding.
 */
data class SyncProgress(
    val liveDone: Int = 0,
    val liveTotal: Int = 0,
    val movieDone: Int = 0,
    val movieTotal: Int = 0,
    val seriesDone: Int = 0,
    val seriesTotal: Int = 0,
) {
    val shelvesDone: Int get() = liveDone + movieDone + seriesDone
    val shelvesTotal: Int get() = liveTotal + movieTotal + seriesTotal

    /** 0f..1f, or null before any shelf is known - an empty total must not read as "finished". */
    val fraction: Float?
        get() = if (shelvesTotal == 0) null else shelvesDone.toFloat() / shelvesTotal
}

/**
 * Search state.
 *
 * **Debounced, and the delay is the feature.** Typing "sky at night" on a remote is eight
 * keystrokes, and a search runs on every one of them. Without a delay the results visibly thrash -
 * "s" matches four thousand titles, "sk" matches nine hundred, "sky" matches forty - and the list
 * jumps under the viewer faster than they can read it. Waiting until the typing pauses gives one
 * settled answer instead of eight wrong ones.
 */
class SearchViewModel(
    private val repository: SearchRepository,
    private val liveCategories: suspend () -> List<LiveCategory>,
    private val vodShelves: suspend () -> List<VodShelf>,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var indexJob: Job? = null

    /** Cached so coverage can be recomputed without going back to the network on every keystroke. */
    private var scopesTotal = 0

    init {
        viewModelScope.launch {
            // The category lists decide what "complete" means, and both are already cached by the
            // browse screens, so this costs a database read rather than a fetch.
            val live = runCatching { liveCategories() }.getOrDefault(emptyList())
            val vod = runCatching { vodShelves() }.getOrDefault(emptyList())
            scopesTotal = live.size + vod.size
            refreshCoverage()
            // Counts change as shelves are browsed, so this stays subscribed rather than sampled
            // once: a viewer who browses a new film category should see the number move.
            repository.observeCounts().collect { counts ->
                _state.update { it.copy(coverage = coverageOf(counts)) }
            }
        }
    }

    private suspend fun refreshCoverage() {
        _state.update { it.copy(coverage = coverageOf(repository.currentCounts())) }
    }

    private fun coverageOf(counts: IndexCounts) = IndexCoverage(
        liveIndexed = counts.liveIndexed,
        movieIndexed = counts.movieIndexed,
        seriesIndexed = counts.seriesIndexed,
        scopesTotal = scopesTotal,
        scopesComplete = counts.scopesComplete,
    )

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()

        val needle = value.trim()
        if (needle.isEmpty()) {
            _state.update { it.copy(hits = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            _state.update { it.copy(isSearching = true) }
            delay(SEARCH_DEBOUNCE_MILLIS)
            val hits = repository.search(needle)
            // A response for an earlier keystroke must not overwrite a newer one. The job is
            // cancelled on each keystroke, but a query already past the database cannot be
            // recalled, so the term is re-checked before the result is accepted.
            if (_state.value.query.trim() != needle) return@launch
            _state.update { it.copy(hits = hits, isSearching = false) }
        }
    }

    fun clearQuery() = onQueryChange("")

    /**
     * Walk the whole catalogue into the index.
     *
     * Opt-in and in the background, because on a provider this size it is a long download and
     * nobody should start it by opening a search screen. It is also the only way search becomes
     * complete, so it is worth one explicit press rather than doing it silently behind the viewer.
     */
    fun startIndexing() {
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch {
            _state.update { it.copy(isIndexing = true, indexError = null, indexedSoFar = 0) }
            try {
                val live = runCatching { liveCategories() }.getOrDefault(emptyList())
                val vod = runCatching { vodShelves() }.getOrDefault(emptyList())
                scopesTotal = live.size + vod.size
                val run = repository.indexEverything(live, vod) { report ->
                    _state.update {
                        it.copy(
                            indexedSoFar = report.added,
                            skippedShelves = report.skipped,
                            syncProgress = SyncProgress(
                                liveDone = report.liveDone,
                                liveTotal = report.liveTotal,
                                movieDone = report.movieDone,
                                movieTotal = report.movieTotal,
                                seriesDone = report.seriesDone,
                                seriesTotal = report.seriesTotal,
                            ),
                        )
                    }
                }
                refreshCoverage()
                _state.update {
                    it.copy(
                        // Named, not raw. `e.message` on a JSON failure is the parser's complaint
                        // plus a slice of the provider's response - technically true and completely
                        // useless to someone sitting in front of a television.
                        indexError = if (run.skippedScopes.isEmpty()) {
                            null
                        } else {
                            val count = run.skippedScopes.size
                            "$count shelf${if (count == 1) "" else "s"} could not be read from " +
                                "your provider and " +
                                "${if (count == 1) "is" else "are"} missing from search. " +
                                "This usually clears if you try again."
                        },
                        skippedShelves = run.skippedScopes.size,
                    )
                }
            } catch (cancellation: CancellationException) {
                // Rethrown, not reported. CancellationException is an Exception, so a plain
                // `catch (e: Exception)` swallows it - which breaks structured concurrency, leaves
                // the job's parent thinking it is still running, and puts "StandaloneCoroutine was
                // cancelled" on screen as though the provider had failed. Pressing "Stop indexing"
                // did exactly that.
                throw cancellation
            } catch (e: Exception) {
                _state.update { it.copy(indexError = e.toUserMessage()) }
            } finally {
                _state.update { it.copy(isIndexing = false) }
            }
        }
    }

    fun cancelIndexing() {
        indexJob?.cancel()
        indexJob = null
        _state.update { it.copy(isIndexing = false) }
    }

    companion object {
        /**
         * Long enough that a burst of D-pad or IME key events collapses into one query, short
         * enough that the list still feels like it is answering you.
         */
        const val SEARCH_DEBOUNCE_MILLIS = 220L

        fun factory(container: AppContainer, profile: ProviderProfile): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = container.searchRepository(profile)
                    return SearchViewModel(
                        repository = repository,
                        liveCategories = { container.liveRepository(profile).cachedCategories() },
                        vodShelves = { container.vodRepository(profile).cachedShelves() },
                    ) as T
                }
            }
    }
}

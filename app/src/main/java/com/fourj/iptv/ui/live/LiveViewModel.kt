package com.fourj.iptv.ui.live

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fourj.iptv.data.remote.toUserMessage
import com.fourj.iptv.data.repository.EpgRepository
import com.fourj.iptv.data.repository.LiveRepository
import com.fourj.iptv.data.repository.SearchRepository
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.domain.model.EpgListing
import com.fourj.iptv.domain.model.LiveCategory
import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.domain.model.ProviderProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveUiState(
    val categories: List<LiveCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<LiveChannel> = emptyList(),
    val query: String = "",
    val isLoadingCategories: Boolean = true,
    val isLoadingChannels: Boolean = false,
    val error: String? = null,
    val playing: LiveChannel? = null,
) {
    /** Search narrows the list client-side; the full list is already cached locally. */
    val visibleChannels: List<LiveChannel>
        get() = if (query.isBlank()) {
            channels
        } else {
            val needle = query.trim()
            channels.filter { it.name.contains(needle, ignoreCase = true) }
        }
}

/** What the channel stream reports back, so loading and content land in one place. */
private data class ChannelLoad(
    val channels: List<LiveChannel> = emptyList(),
    val loading: Boolean = false,
)

// `flatMapLatest` is still marked experimental, so the category load opts in rather than leaving
// the warning to be rediscovered on every build.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveViewModel(
    private val repository: LiveRepository,
    private val epgRepository: EpgRepository,
    private val searchRepository: SearchRepository,
    val profile: ProviderProfile,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    /**
     * What is on now, and what follows, keyed by channel.
     *
     * Derived from the cached guide on a ticker rather than re-queried, because "now" is a
     * function of the clock: a programme ending does not change the database, it just stops
     * being current. A 30s tick is finer-grained than the eye can read off a television.
     */
    private val _nowNext = MutableStateFlow<Map<Int, NowNext>>(emptyMap())
    val nowNext: StateFlow<Map<Int, NowNext>> = _nowNext.asStateFlow()

    /**
     * The latest cached guide, held so the ticker can re-derive without re-querying.
     *
     * The previous version re-subscribed to the database flow every tick via `first()`. That both
     * ran a Room query on the main dispatcher - which Room rejects outright with
     * `assertNotSuspendingTransaction` - and threw away the subscription 120 times an hour for
     * data that had not changed.
     */
    private var cachedGuide: List<EpgListing> = emptyList()

    init {
        loadCategories()
        observeCategories()
        observeSelectedCategory()
        observeGuide()
    }

    private fun observeGuide() {
        viewModelScope.launch {
            epgRepository.observeAll().collect { listings ->
                cachedGuide = listings
                _nowNext.value = listings.toNowNext(nowSeconds())
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(CLOCK_TICK_MS)
                _nowNext.value = cachedGuide.toNowNext(nowSeconds())
            }
        }
    }

    /**
     * Ask for a guide once the channel list is known, for the top of the list only.
     *
     * The panel has no bulk guide call, so this is one request per channel - bounded to the
     * visible window in [EpgRepository] to avoid hammering a rate-limited provider.
     */
    private fun requestGuideForVisibleChannels() {
        val ids = _state.value.visibleChannels.map { it.streamId }
        if (ids.isEmpty()) return
        viewModelScope.launch { epgRepository.ensureGuideFor(ids) }
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    /**
     * Add a freshly loaded shelf to the search index.
     *
     * Best-effort: a failure here must not disturb playback or browsing, so it is swallowed after
     * being logged. The index is a convenience, and letting it break the thing the viewer was
     * actually doing would be a bad trade.
     */
    private fun indexChannelsForSearch(channels: List<LiveChannel>) {
        if (channels.isEmpty()) return
        val categoryName = _state.value.categories
            .firstOrNull { it.id == _state.value.selectedCategoryId }
            ?.name
        viewModelScope.launch {
            runCatching { searchRepository.indexChannels(channels, categoryName) }
                .onFailure { Log.w(TAG, "could not index channels for search", it) }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            repository.refreshCategories()
                .onFailure { throwable ->
                    _state.update {
                        it.copy(isLoadingCategories = false, error = throwable.toUserMessage())
                    }
                }
        }
    }

    private fun observeCategories() {
        viewModelScope.launch {
            repository.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories, isLoadingCategories = false) }
                // Land on the first category so the screen is never empty on arrival.
                if (_state.value.selectedCategoryId == null && categories.isNotEmpty()) {
                    selectCategory(categories.first().id)
                }
            }
        }
    }

    /**
     * Fetch the selected category if it is not cached yet, then follow the cache.
     *
     * `flatMapLatest` means switching category cancels the previous category's work rather than
     * racing two loads to update the same field.
     */
    private fun observeSelectedCategory() {
        viewModelScope.launch {
            _state.map { it.selectedCategoryId }
                .distinctUntilChanged()
                .flatMapLatest { categoryId ->
                    if (categoryId == null) {
                        flowOf(ChannelLoad())
                    } else {
                        flow {
                            emit(ChannelLoad(loading = true))
                            repository.ensureCategoryLoaded(categoryId).onFailure { throwable ->
                                _state.update { it.copy(error = throwable.toUserMessage()) }
                            }
                            emitAll(
                                repository.observeChannels(categoryId)
                                    .map { ChannelLoad(channels = it, loading = false) },
                            )
                        }
                    }
                }
                .collect { load ->
                    _state.update { it.copy(channels = load.channels, isLoadingChannels = load.loading) }
                    requestGuideForVisibleChannels()
                    // Feed the search index as channels arrive. Free coverage: these rows have just
                    // been downloaded anyway, so indexing them costs one write and makes the shelf
                    // searchable without the viewer ever asking for it.
                    indexChannelsForSearch(load.channels)
                }
        }
    }

    fun selectCategory(categoryId: String) {
        _state.update { it.copy(selectedCategoryId = categoryId, query = "") }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    /** The channel in front of the viewer is the one guide worth being current on. */
    fun play(channel: LiveChannel) {
        _state.update { it.copy(playing = channel) }
        viewModelScope.launch { epgRepository.refreshNow(channel.streamId) }
    }

    fun onPlaybackFinished() = _state.update { it.copy(playing = null) }

    /**
     * Look up a channel by id, for search results.
     *
     * Returns null when the channel is not in the local cache. A search hit can name a channel the
     * app has indexed but not cached in full - the index holds only names and ids - so the caller
     * has to handle a miss rather than assume the hit is playable.
     */
    suspend fun findChannel(streamId: Int): LiveChannel? = repository.findChannel(streamId)

    /**
     * Make sure a channel's category is on screen before playing it.
     *
     * Search can surface a channel from a shelf the viewer has never opened, and the player is
     * driven by whatever the browse screen currently has loaded. Without this, picking a search
     * result from an unopened category would start playback for a channel the grid knows nothing
     * about - and "now playing" would be the only evidence it had ever been found.
     */
    suspend fun revealChannel(channel: LiveChannel) {
        val categoryId = channel.categoryId ?: return
        repository.ensureCategoryLoaded(categoryId)
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    /** Signed URL for a channel, including the headers the panel requires. */
    fun streamUrl(channel: LiveChannel): String = repository.streamUrl(channel)

    fun requestHeaders(channel: LiveChannel): Map<String, String> = buildMap {
        channel.httpUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        channel.httpReferrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
    }

    companion object {
        private const val CLOCK_TICK_MS = 30_000L
        private const val TAG = "4J"

        fun factory(container: AppContainer, profile: ProviderProfile) = viewModelFactory {
            initializer {
                LiveViewModel(
                    repository = container.liveRepository(profile),
                    epgRepository = container.epgRepository(profile),
                    searchRepository = container.searchRepository(profile),
                    profile = profile,
                )
            }
        }
    }
}

/** What is on a channel right now, and what comes next. */
data class NowNext(
    val current: EpgListing,
    val next: EpgListing?,
) {
    /** 0f..1f through the current programme, for a progress bar. */
    fun progress(nowSeconds: Long): Float {
        val span = (current.end - current.start).toFloat()
        if (span <= 0f) return 0f
        return ((nowSeconds - current.start) / span).coerceIn(0f, 1f)
    }

    fun endsInMinutes(nowSeconds: Long): Long =
        ((current.end - nowSeconds) / 60).coerceAtLeast(0)
}

/**
 * Reduce a flat list of cached listings to one [NowNext] per channel.
 *
 * A panel can return overlapping or out-of-order listings, so "current" is resolved by finding
 * the listing that actually spans the clock rather than trusting the `now_playing` flag.
 */
internal fun List<EpgListing>.toNowNext(nowSeconds: Long): Map<Int, NowNext> =
    groupBy { it.streamId }
        .mapNotNull { (streamId, listings) ->
            val ordered = listings.sortedBy { it.start }
            val current = ordered.firstOrNull { it.start <= nowSeconds && it.end > nowSeconds }
                ?: ordered.firstOrNull { it.nowPlaying }
                ?: return@mapNotNull null
            val next = ordered.firstOrNull { it.start > current.start && it.id != current.id }
            streamId to NowNext(current, next)
        }
        .toMap()

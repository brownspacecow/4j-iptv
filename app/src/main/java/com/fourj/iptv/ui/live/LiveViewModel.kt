package com.fourj.iptv.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fourj.iptv.data.remote.toUserMessage
import com.fourj.iptv.data.repository.LiveRepository
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.domain.model.LiveCategory
import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.domain.model.ProviderProfile
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

class LiveViewModel(
    private val repository: LiveRepository,
    val profile: ProviderProfile,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    init {
        loadCategories()
        observeCategories()
        observeSelectedCategory()
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
                }
        }
    }

    fun selectCategory(categoryId: String) {
        _state.update { it.copy(selectedCategoryId = categoryId, query = "") }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun play(channel: LiveChannel) = _state.update { it.copy(playing = channel) }

    fun onPlaybackFinished() = _state.update { it.copy(playing = null) }

    /** Signed URL for a channel, including the headers the panel requires. */
    fun streamUrl(channel: LiveChannel): String = repository.streamUrl(channel)

    fun requestHeaders(channel: LiveChannel): Map<String, String> = buildMap {
        channel.httpUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        channel.httpReferrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.clearCache()
            onDone()
        }
    }

    companion object {
        fun factory(container: AppContainer, profile: ProviderProfile) = viewModelFactory {
            initializer { LiveViewModel(container.liveRepository(profile), profile) }
        }
    }
}

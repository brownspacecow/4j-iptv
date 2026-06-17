package com.iptv.fourj.data.repository

import android.util.Log
import android.content.Context
import com.iptv.fourj.data.api.StreamType
import com.iptv.fourj.data.api.XtreamApi
import com.iptv.fourj.data.db.ProviderStore
import com.iptv.fourj.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class CacheLoadState(
    val isLoading: Boolean = false,
    val message: String = ""
)

@Serializable
private data class ContentCacheSnapshot(
    val providerId: Long,
    val liveStreams: List<LiveStream> = emptyList(),
    val vodStreams: List<VodStream> = emptyList(),
    val seriesStreams: List<SeriesStream> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
)

class IptvRepository(
    private val providerStore: ProviderStore,
    private val context: Context
) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }
    private val cacheDir: File get() = File(context.filesDir, "content_cache")

    private var cachedApi: XtreamApi? = null
    private var cachedProviderId: Long = -1

    @Volatile
    private var cachedLiveStreams: List<LiveStream>? = null
    @Volatile
    private var cachedVodStreams: List<VodStream>? = null
    @Volatile
    private var cachedSeriesStreams: List<SeriesStream>? = null

    private val _cacheLoadState = MutableStateFlow(CacheLoadState())
    val cacheLoadState: StateFlow<CacheLoadState> = _cacheLoadState

    private suspend fun getApi(): XtreamApi {
        val provider = providerStore.getActiveProvider()
            ?: throw IllegalStateException("No active provider - please add a provider first")
        Log.d("IptvRepo", "Active provider: id=${provider.id} url=${provider.serverUrl} user=${provider.username}")
        if (cachedProviderId != provider.id) {
            clearCache()
            cachedApi = XtreamApi(provider)
            cachedProviderId = provider.id
        }
        return cachedApi!!
    }

    suspend fun login(provider: Provider): Result<LoginResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val api = XtreamApi(provider)
            val response = api.login()
            if (response.userInfo.auth == 1) response
            else throw Exception("Authentication failed: ${response.userInfo.message}")
        }
    }

    suspend fun getLiveCategories(): List<LiveCategory> = withContext(Dispatchers.IO) {
        try {
            val result = getApi().getLiveCategories()
            Log.d("IptvRepo", "getLiveCategories: got ${result.size} categories")
            result
        } catch (e: Exception) {
            Log.e("IptvRepo", "getLiveCategories failed", e)
            throw e
        }
    }

    suspend fun getVodCategories(): List<VodCategory> = withContext(Dispatchers.IO) {
        try {
            val result = getApi().getVodCategories()
            Log.d("IptvRepo", "getVodCategories: got ${result.size} categories")
            result
        } catch (e: Exception) {
            Log.e("IptvRepo", "getVodCategories failed", e)
            throw e
        }
    }

    suspend fun getSeriesCategories(): List<SeriesCategory> = withContext(Dispatchers.IO) {
        try {
            val result = getApi().getSeriesCategories()
            Log.d("IptvRepo", "getSeriesCategories: got ${result.size} categories")
            result
        } catch (e: Exception) {
            Log.e("IptvRepo", "getSeriesCategories failed", e)
            throw e
        }
    }

    suspend fun preloadContentCache() {
        if (_cacheLoadState.value.isLoading || isCacheLoaded()) {
            Log.d("IptvRepo", "preloadContentCache: already in progress, skipping")
            return
        }
        try {
            if (restoreCacheFromDisk()) {
                _cacheLoadState.value = CacheLoadState(false, "Ready")
                return
            }
            val api = getApi()
            _cacheLoadState.value = CacheLoadState(true, "Loading live channels...")
            cachedLiveStreams = cachedLiveStreams ?: api.getLiveStreams(null).also {
                Log.d("IptvRepo", "preloaded ${it.size} live streams")
            }
            _cacheLoadState.value = CacheLoadState(true, "Loading movies...")
            cachedVodStreams = cachedVodStreams ?: api.getVodStreams(null).also {
                Log.d("IptvRepo", "preloaded ${it.size} vod streams")
            }
            _cacheLoadState.value = CacheLoadState(true, "Loading series...")
            cachedSeriesStreams = cachedSeriesStreams ?: api.getSeriesStreams(null).also {
                Log.d("IptvRepo", "preloaded ${it.size} series streams")
            }
            persistCacheToDisk()
            _cacheLoadState.value = CacheLoadState(false, "Ready")
        } catch (e: Exception) {
            Log.w("IptvRepo", "preloadContentCache failed", e)
            _cacheLoadState.value = CacheLoadState(false, e.message ?: "Cache failed")
        }
    }

    suspend fun getSeriesInfo(seriesId: String): SeriesInfo = withContext(Dispatchers.IO) {
        getApi().getSeriesInfo(seriesId)
    }

    fun getStreamUrl(type: StreamType, streamId: String, extension: String = "ts"): String {
        val api = cachedApi ?: throw IllegalStateException("No active provider - load content first")
        return api.getStreamUrl(type, streamId, extension)
    }

    fun clearCache() {
        cachedApi = null
        cachedProviderId = -1
        cachedLiveStreams = null
        cachedVodStreams = null
        cachedSeriesStreams = null
        _cacheLoadState.value = CacheLoadState()
    }

    private fun cacheFile(providerId: Long): File = File(cacheDir, "provider_$providerId.json")

    private suspend fun restoreCacheFromDisk(): Boolean {
        val provider = providerStore.getActiveProvider()
            ?: return false
        val file = cacheFile(provider.id)
        if (!file.exists()) return false

        return runCatching {
            val snapshot = json.decodeFromString(ContentCacheSnapshot.serializer(), file.readText())
            if (snapshot.providerId != provider.id) return false
            cachedLiveStreams = snapshot.liveStreams
            cachedVodStreams = snapshot.vodStreams
            cachedSeriesStreams = snapshot.seriesStreams
            cachedProviderId = provider.id
            _cacheLoadState.value = CacheLoadState(false, "Ready")
            Log.d("IptvRepo", "Restored cache from disk for provider ${provider.id}")
            true
        }.getOrElse {
            Log.w("IptvRepo", "Failed to restore cache from disk", it)
            false
        }
    }

    private suspend fun <T> loadCached(
        cached: T?,
        fetch: suspend XtreamApi.() -> T,
        store: (T) -> Unit
    ): T {
        cached?.let { return it }
        val api = getApi()
        val loaded = api.fetch()
        store(loaded)
        return loaded
    }

    private fun persistCacheToDisk() {
        val providerId = cachedProviderId.takeIf { it > 0 } ?: return
        val live = cachedLiveStreams ?: return
        val vod = cachedVodStreams ?: return
        val series = cachedSeriesStreams ?: return
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val snapshot = ContentCacheSnapshot(
                providerId = providerId,
                liveStreams = live,
                vodStreams = vod,
                seriesStreams = series
            )
            cacheFile(providerId).writeText(json.encodeToString(ContentCacheSnapshot.serializer(), snapshot))
            Log.d("IptvRepo", "Persisted cache to disk for provider $providerId")
        }.onFailure {
            Log.w("IptvRepo", "Failed to persist cache to disk", it)
        }
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            loadCached(cachedLiveStreams, { getLiveStreams(null) }) { cachedLiveStreams = it }
        } else {
            getApi().getLiveStreams(categoryId)
        }
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            loadCached(cachedVodStreams, { getVodStreams(null) }) { cachedVodStreams = it }
        } else {
            getApi().getVodStreams(categoryId)
        }
    }

    suspend fun getSeriesStreams(categoryId: String? = null): List<SeriesStream> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            loadCached(cachedSeriesStreams, { getSeriesStreams(null) }) { cachedSeriesStreams = it }
        } else {
            getApi().getSeriesStreams(categoryId)
        }
    }

    fun searchLive(query: String): List<LiveStream> {
        if (query.isBlank()) return emptyList()
        return cachedLiveStreams?.filter { it.name.contains(query, ignoreCase = true) } ?: emptyList()
    }

    fun searchVod(query: String): List<VodStream> {
        if (query.isBlank()) return emptyList()
        return cachedVodStreams?.filter { it.name.contains(query, ignoreCase = true) } ?: emptyList()
    }

    fun searchSeries(query: String): List<SeriesStream> {
        if (query.isBlank()) return emptyList()
        return cachedSeriesStreams?.filter { it.name.contains(query, ignoreCase = true) } ?: emptyList()
    }

    fun isCacheLoaded(): Boolean = cachedLiveStreams != null && cachedVodStreams != null && cachedSeriesStreams != null
}

package com.iptv.fourj.data.repository

import com.iptv.fourj.data.api.StreamType
import com.iptv.fourj.data.api.XtreamApi
import com.iptv.fourj.data.db.ProviderStore
import com.iptv.fourj.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IptvRepository(private val providerStore: ProviderStore) {

    private var cachedApi: XtreamApi? = null
    private var cachedProviderId: Long = -1

    private var cachedLiveStreams: List<LiveStream>? = null
    private var cachedVodStreams: List<VodStream>? = null
    private var cachedSeriesStreams: List<SeriesStream>? = null

    private suspend fun getApi(): XtreamApi {
        val provider = providerStore.getActiveProvider()
            ?: throw IllegalStateException("No active provider")
        if (cachedProviderId != provider.id) {
            cachedApi = XtreamApi(provider)
            cachedProviderId = provider.id
            clearCache()
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
        getApi().getLiveCategories()
    }

    suspend fun getVodCategories(): List<VodCategory> = withContext(Dispatchers.IO) {
        getApi().getVodCategories()
    }

    suspend fun getSeriesCategories(): List<SeriesCategory> = withContext(Dispatchers.IO) {
        getApi().getSeriesCategories()
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
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            if (cachedLiveStreams == null) {
                cachedLiveStreams = getApi().getLiveStreams(null)
            }
            cachedLiveStreams!!
        } else {
            getApi().getLiveStreams(categoryId)
        }
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            if (cachedVodStreams == null) {
                cachedVodStreams = getApi().getVodStreams(null)
            }
            cachedVodStreams!!
        } else {
            getApi().getVodStreams(categoryId)
        }
    }

    suspend fun getSeriesStreams(categoryId: String? = null): List<SeriesStream> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            if (cachedSeriesStreams == null) {
                cachedSeriesStreams = getApi().getSeriesStreams(null)
            }
            cachedSeriesStreams!!
        } else {
            getApi().getSeriesStreams(categoryId)
        }
    }

    suspend fun localSearchLive(query: String): List<LiveStream> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val all = getLiveStreams(null)
        all.filter { it.name.contains(query, ignoreCase = true) }
    }

    suspend fun localSearchVod(query: String): List<VodStream> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val all = getVodStreams(null)
        all.filter { it.name.contains(query, ignoreCase = true) }
    }

    suspend fun localSearchSeries(query: String): List<SeriesStream> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val all = getSeriesStreams(null)
        all.filter { it.name.contains(query, ignoreCase = true) }
    }

    suspend fun searchLiveStreams(query: String): List<LiveStream> = withContext(Dispatchers.IO) {
        localSearchLive(query)
    }

    suspend fun searchVodStreams(query: String): List<VodStream> = withContext(Dispatchers.IO) {
        localSearchVod(query)
    }

    suspend fun searchSeriesStreams(query: String): List<SeriesStream> = withContext(Dispatchers.IO) {
        localSearchSeries(query)
    }
}

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

    private suspend fun getApi(): XtreamApi {
        val provider = providerStore.getActiveProvider()
            ?: throw IllegalStateException("No active provider")
        if (cachedProviderId != provider.id) {
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
        getApi().getLiveCategories()
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> = withContext(Dispatchers.IO) {
        getApi().getLiveStreams(categoryId)
    }

    suspend fun getVodCategories(): List<VodCategory> = withContext(Dispatchers.IO) {
        getApi().getVodCategories()
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> = withContext(Dispatchers.IO) {
        getApi().getVodStreams(categoryId)
    }

    suspend fun getSeriesCategories(): List<SeriesCategory> = withContext(Dispatchers.IO) {
        getApi().getSeriesCategories()
    }

    suspend fun getSeriesStreams(categoryId: String? = null): List<SeriesStream> = withContext(Dispatchers.IO) {
        getApi().getSeriesStreams(categoryId)
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
    }
}

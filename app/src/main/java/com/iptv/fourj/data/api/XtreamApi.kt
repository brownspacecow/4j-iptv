package com.iptv.fourj.data.api

import com.iptv.fourj.data.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class XtreamApi(private val provider: Provider) {

    private val baseUrl = provider.serverUrl.trimEnd('/')

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
    }

    private fun buildAuthUrl(action: String = "", extraParams: Map<String, String> = emptyMap()): String {
        val params = buildMap {
            put("username", provider.username)
            put("password", provider.password)
            if (action.isNotBlank()) put("action", action)
            putAll(extraParams)
        }.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        return "$baseUrl/player_api.php?$params"
    }

    suspend fun login(): LoginResponse {
        val url = buildAuthUrl()
        val response = client.get(url)
        val text = response.bodyAsText()
        return Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString(text)
    }

    suspend fun getLiveCategories(): List<LiveCategory> {
        val url = buildAuthUrl("get_live_categories")
        return client.get(url).body()
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> {
        val extra = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        val url = buildAuthUrl("get_live_streams", extra)
        return client.get(url).body()
    }

    suspend fun getVodCategories(): List<VodCategory> {
        val url = buildAuthUrl("get_vod_categories")
        return client.get(url).body()
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> {
        val extra = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        val url = buildAuthUrl("get_vod_streams", extra)
        return client.get(url).body()
    }

    suspend fun getSeriesCategories(): List<SeriesCategory> {
        val url = buildAuthUrl("get_series_categories")
        return client.get(url).body()
    }

    suspend fun getSeriesStreams(categoryId: String? = null): List<SeriesStream> {
        val extra = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        val url = buildAuthUrl("get_series", extra)
        return client.get(url).body()
    }

    suspend fun getSeriesInfo(seriesId: String): SeriesInfo {
        val url = buildAuthUrl("get_series_info", mapOf("series_id" to seriesId))
        return client.get(url).body()
    }

    suspend fun getShortEpg(streamId: String): List<EpgListing> {
        val url = buildAuthUrl("get_short_epg", mapOf("stream_id" to streamId))
        return client.get(url).body()
    }

    fun getLiveStreamUrl(streamId: String): String {
        return "$baseUrl/live/${provider.username}/${provider.password}/$streamId.ts"
    }

    fun getVodStreamUrl(streamId: String, extension: String): String {
        return "$baseUrl/movie/${provider.username}/${provider.password}/$streamId.$extension"
    }

    fun getSeriesStreamUrl(streamId: String, extension: String): String {
        return "$baseUrl/series/${provider.username}/${provider.password}/$streamId.$extension"
    }

    fun getStreamUrl(type: StreamType, streamId: String, extension: String = "ts"): String {
        return when (type) {
            StreamType.LIVE -> getLiveStreamUrl(streamId)
            StreamType.MOVIE -> getVodStreamUrl(streamId, extension)
            StreamType.SERIES -> getSeriesStreamUrl(streamId, extension)
        }
    }

    companion object {
        fun buildTestClient(serverUrl: String, username: String, password: String): XtreamApi {
            return XtreamApi(
                Provider(
                    name = "Test",
                    serverUrl = serverUrl,
                    username = username,
                    password = password
                )
            )
        }
    }
}

enum class StreamType {
    LIVE, MOVIE, SERIES
}

package com.iptv.fourj.data.api

import android.util.Log
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
        install(io.ktor.client.plugins.DefaultRequest) {
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
        }
    }

    private val safeJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
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

    private suspend inline fun <reified T> safeGet(url: String): T {
        val response = client.get(url)
        val status = response.status
        val text = response.bodyAsText()
        Log.d("XtreamApi", "GET $url => HTTP $status body=${text.take(500)}")
        if (status.value == 404) {
            throw Exception("Server returned 404 Not Found.\nThe server may not be reachable from this network, or the URL is incorrect.")
        }
        if (status.value != 200) {
            throw Exception("Server returned HTTP ${status.value}: ${text.take(200)}")
        }
        if (text.isBlank()) {
            throw Exception("Server returned empty response")
        }
        if (text.trimStart().startsWith("{") && text.contains("\"user_info\"")) {
            val login = safeJson.decodeFromString<LoginResponse>(text)
            if (login.userInfo.auth != 1) {
                throw Exception("Auth failed: ${login.userInfo.message.ifBlank { "Server rejected credentials" }}")
            }
            throw Exception("Server returned auth response instead of expected data")
        }
        return safeJson.decodeFromString<T>(text)
    }

    suspend fun login(): LoginResponse {
        val url = buildAuthUrl()
        val response = client.get(url)
        val status = response.status
        val text = response.bodyAsText()
        Log.d("XtreamApi", "LOGIN $url => HTTP $status body=${text.take(500)}")

        if (status.value != 200) {
            throw Exception("Server returned HTTP ${status.value}: ${text.take(200)}")
        }
        if (text.isBlank()) {
            throw Exception("Server returned empty response")
        }
        return safeJson.decodeFromString(text)
    }

    suspend fun getLiveCategories(): List<LiveCategory> {
        val url = buildAuthUrl("get_live_categories")
        return safeGet(url)
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> {
        val extra = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        val url = buildAuthUrl("get_live_streams", extra)
        return safeGet(url)
    }

    suspend fun getVodCategories(): List<VodCategory> {
        val url = buildAuthUrl("get_vod_categories")
        return safeGet(url)
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> {
        val extra = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        val url = buildAuthUrl("get_vod_streams", extra)
        return safeGet(url)
    }

    suspend fun getSeriesCategories(): List<SeriesCategory> {
        val url = buildAuthUrl("get_series_categories")
        return safeGet(url)
    }

    suspend fun getSeriesStreams(categoryId: String? = null): List<SeriesStream> {
        val extra = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        val url = buildAuthUrl("get_series", extra)
        return safeGet(url)
    }

    suspend fun getSeriesInfo(seriesId: String): SeriesInfo {
        val url = buildAuthUrl("get_series_info", mapOf("series_id" to seriesId))
        return safeGet(url)
    }

    suspend fun getShortEpg(streamId: String): List<EpgListing> {
        val url = buildAuthUrl("get_short_epg", mapOf("stream_id" to streamId))
        return safeGet(url)
    }

    suspend fun searchLiveStreams(query: String): List<LiveStream> {
        val url = buildAuthUrl("get_live_streams", mapOf("search" to query))
        return safeGet(url)
    }

    suspend fun searchVodStreams(query: String): List<VodStream> {
        val url = buildAuthUrl("get_vod_streams", mapOf("search" to query))
        return safeGet(url)
    }

    suspend fun searchSeriesStreams(query: String): List<SeriesStream> {
        val url = buildAuthUrl("get_series", mapOf("search" to query))
        return safeGet(url)
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

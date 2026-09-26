package com.fourj.iptv.data.remote

import com.fourj.iptv.data.remote.xtream.XtreamApi
import com.fourj.iptv.data.remote.xtream.XtreamAuthInterceptor
import com.fourj.iptv.domain.model.ProviderProfile
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Builds the HTTP clients the app uses.
 *
 * Two clients, because they have genuinely different jobs:
 *  - [apiClient] talks JSON to the panel API and should give up quickly.
 *  - [playerClient] is handed to ExoPlayer for media segments, where a slow provider is normal
 *    and an aggressive read timeout causes stalls rather than clean errors.
 */
object XtreamNetwork {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    fun apiClient(profile: ProviderProfile, debugLogging: Boolean): OkHttpClient =
        baseBuilder()
            .addInterceptor(XtreamAuthInterceptor(profile.username, profile.password))
            .apply {
                if (debugLogging) {
                    // HEADER-level logging only. BODY would print the URL, and the URL carries the
                    // username and password on every single request.
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().apply {
                            level = okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS
                            redactHeader("Authorization")
                        },
                    )
                }
            }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    fun playerClient(): OkHttpClient = baseBuilder()
        .connectTimeout(PLAYER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PLAYER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun createApi(profile: ProviderProfile, debugLogging: Boolean): XtreamApi =
        Retrofit.Builder()
            .baseUrl(profile.baseUrl.trimEnd('/') + "/")
            .client(apiClient(profile, debugLogging))
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
            .create(XtreamApi::class.java)

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)

    private const val JSON_MEDIA_TYPE = "application/json"
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val PLAYER_CONNECT_TIMEOUT_SECONDS = 20L
    private const val PLAYER_READ_TIMEOUT_SECONDS = 60L
}

/**
 * Turns a failure into something worth showing a person sitting ten feet from a screen.
 *
 * The distinction that matters most in practice: a *rejected* credential is not a *network*
 * problem, and telling someone to "check your internet connection" when their password is wrong
 * wastes their time.
 */
fun Throwable.toUserMessage(): String = when (this) {
    is retrofit2.HttpException -> when (code()) {
        401, 403 -> "The provider rejected these credentials."
        404 -> "This server has no Xtream Codes API at that address. Check the port."
        429 -> "Too many requests. Wait a moment and try again."
        in 500..599 -> "The provider's server is having trouble (${code()}). Try again shortly."
        else -> "The provider returned an unexpected response (${code()})."
    }

    is IOException -> when (this) {
        is java.net.SocketTimeoutException -> "The provider took too long to respond."
        is java.net.UnknownHostException -> "That server address could not be found."
        is java.net.ConnectException -> "Could not connect to that server. Check the address and port."
        else -> "Network problem: ${message ?: "connection failed"}."
    }

    else -> message ?: "Something went wrong."
}

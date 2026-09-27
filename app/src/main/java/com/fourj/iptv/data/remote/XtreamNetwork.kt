package com.fourj.iptv.data.remote

import com.fourj.iptv.data.remote.xtream.VodApi
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
 *  - [createVodApi] is deliberately slower still, because film metadata is a big response.
 *
 * There is no client for media here, and there was one until this was checked. `playerClient` built
 * a 20s/60s client for ExoPlayer and its comment claimed it was "handed to ExoPlayer for media
 * segments" - it never was. Media goes through Media3's own `DefaultHttpDataSource`, configured in
 * the player screens, so the client was built, stored on the container and never read. Its intent
 * is now honored where the media actually flows: see the timeouts in `PlayerScreen`.
 */
object XtreamNetwork {

    /**
     * The lenient JSON configuration, exposed for the one place that decodes a response by hand.
     *
     * Shared rather than duplicated so it cannot drift from what Retrofit's converter accepts -
     * a response the converter tolerates and one a second, stricter instance rejects would be a
     * maddening bug to chase.
     */
    val json: Json = Json {
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

    fun createApi(profile: ProviderProfile, debugLogging: Boolean): XtreamApi =
        Retrofit.Builder()
            .baseUrl(profile.baseUrl.trimEnd('/') + "/")
            .client(apiClient(profile, debugLogging))
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
            .create(XtreamApi::class.java)

    /**
     * The VOD half of the API.
     *
     * A separate [Retrofit] instance and therefore a separate OkHttp client, because the live
     * client's short API timeouts are wrong for film metadata: a series with hundreds of episodes
     * is a big response, and timing it out at 30s would fail requests that are merely slow.
     */
    fun createVodApi(profile: ProviderProfile): VodApi =
        Retrofit.Builder()
            .baseUrl(profile.baseUrl.trimEnd('/') + "/")
            .client(
                OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .addInterceptor(XtreamAuthInterceptor(profile.username, profile.password))
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .build(),
            )
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
            .create(VodApi::class.java)

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)

    private const val JSON_MEDIA_TYPE = "application/json"
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
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
        408 -> "The provider took too long to answer."
        429 -> "Too many requests. Wait a moment and try again."
        in 500..599 -> "The provider's server is having trouble (${code()}). Try again shortly."
        else -> "The provider returned an unexpected response (${code()})."
    }

    is kotlinx.serialization.SerializationException ->
        // The panel cut a large response short. Retries are already exhausted by the time this
        // surfaces, so say something the user can act on rather than leaking JSON internals.
        //
        // Deliberately not naming what was cut off. This is reached from a live category, a film
        // shelf and a series shelf alike, and a viewer whose *films* failed does not learn anything
        // from being told to look at their channel list.
        "The provider's reply was cut off before it finished. This usually clears on a retry - " +
            "if it keeps happening this list may be too large for this provider."

    is java.io.EOFException ->
        "The provider closed the connection before finishing its reply. Try again."

    is IOException -> when (this) {
        is java.net.SocketTimeoutException -> "The provider took too long to respond."
        is java.net.UnknownHostException -> "That server address could not be found."
        is java.net.ConnectException -> "Could not connect to that server. Check the address and port."
        else -> "Network problem: ${message ?: "connection failed"}."
    }

    else -> message ?: "Something went wrong."
}

package com.fourj.iptv.data.remote

import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.domain.model.Movie
import com.fourj.iptv.domain.model.ProviderProfile

/**
 * Builds the URLs a panel serves media from.
 *
 * Xtream Codes panels generally expose a live stream at
 * `{base}/live/{username}/{password}/{stream_id}.{ext}`, but the shape varies enough that the
 * `direct_source` field, when present, is the more reliable answer. [liveStream] prefers it.
 *
 * Free of Android dependencies so it can be unit tested directly.
 */
object StreamUrls {

    private const val DEFAULT_EXTENSION = "ts"

    /**
     * The playable URL for a live channel.
     *
     * `direct_source` wins when it is an absolute http(s) URL, because panels that populate it
     * have already decided how the stream is meant to be reached. Otherwise the conventional
     * `live/` path is assembled using the channel's declared container extension.
     */
    fun liveStream(profile: ProviderProfile, channel: LiveChannel): String {
        directSource(channel.directSource)?.let { return it }

        val extension = channel.containerExtension
            ?.trim()
            ?.lowercase()
            ?.removePrefix(".")
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_EXTENSION

        return buildString {
            append(profile.baseUrl)
            append("/live/")
            append(profile.username)
            append('/')
            append(profile.password)
            append('/')
            append(channel.streamId)
            append('.')
            append(extension)
        }
    }

    /** The provider-issued playlist URL, used as the M3U fallback when a panel's CDN is blocked. */
    fun playlist(profile: ProviderProfile): String = buildString {
        append(profile.baseUrl)
        append("/get.php")
        append("?username=")
        append(profile.username)
        append("&password=")
        append(profile.password)
        append("&type=m3u_plus")
        append("&output=ts")
    }

    private fun directSource(value: String?): String? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val lower = candidate.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) candidate else null
    }

    /**
     * The playable URL for an on-demand film.
     *
     * Same shape as a live stream but without the container extension: films are served from a
     * fixed `movie/` path and the panel picks the container itself.
     */
    fun movie(profile: ProviderProfile, movie: Movie): String = buildString {
        append(profile.baseUrl)
        append("/movie/")
        append(profile.username)
        append('/')
        append(profile.password)
        append('/')
        append(movie.id)
        append('.')
        append(movie.containerExtension?.takeIf { it.isNotBlank() } ?: DEFAULT_MOVIE_EXTENSION)
    }
}

private const val DEFAULT_MOVIE_EXTENSION = "mp4"

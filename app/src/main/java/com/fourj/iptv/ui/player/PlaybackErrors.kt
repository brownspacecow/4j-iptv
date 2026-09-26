package com.fourj.iptv.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turn a playback failure into something that tells the user what to actually do.
 *
 * The generic "this channel would not play, it may be offline or restricted" that this replaced
 * was actively misleading. Channels were observed failing because their `direct_source` pointed
 * at a *different CDN host* from the panel - so the channel was not offline and not restricted,
 * the app just could not reach the origin it was handed. Telling someone to check their
 * subscription over a DNS or timeout failure sends them down the wrong path entirely.
 */
internal fun describePlaybackError(error: PlaybackException): String {
    val cause = generateSequence<Throwable>(error) { it.cause }
        .firstOrNull { it !is PlaybackException }
        ?: error

    return when (cause) {
        is UnrecognizedInputFormatException ->
            "This channel uses a video or audio format this device cannot play."

        is UnknownHostException ->
            "The address this channel streams from could not be found. The provider's link for " +
                "it may be broken."

        is SocketTimeoutException ->
            "The stream did not respond in time. It may be overloaded."

        is ConnectException ->
            "Could not connect to the stream's server."

        is IOException ->
            "The stream was cut off. The provider may be limiting connections."

        else -> when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "Could not reach the stream. The provider's server for this channel may be down."

            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            -> "This device could not decode the channel. Try the other build of the app."

            else -> "This channel would not play. It may be offline or restricted."
        }
    }
}

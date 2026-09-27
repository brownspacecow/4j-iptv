package com.fourj.iptv.ui.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
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
            -> {
                // Say which decoder gave up, because the remedy differs by flavor and by codec.
                // The wording comes from the flavor: only the `full` build has a software video
                // decoder to fall back to, so only it can honestly claim to have tried one.
                if (isVideoRendererError(error)) {
                    videoDecodeFailureMessage()
                } else {
                    "This device could not decode the audio. The 'full' build adds software " +
                        "decoders for Dolby and DTS if this one is silent."
                }
            }

            else -> "This channel would not play. It may be offline or restricted."
        }
    }
}

/**
 * One track group as the player sees it: what kind it is, whether anything can decode it, and the
 * format it is in.
 *
 * A holder rather than three parallel lists because the three belong together - reporting "no
 * decoder" without saying which format would be the least useful version of the message.
 */
internal data class TrackSupport(
    val type: Int,
    val supported: Boolean,
    val sampleMimeType: String? = null,
)

/**
 * The video track group that no renderer on this device can decode, or null if video is fine (or
 * there is no video, which is normal for a radio channel).
 *
 * Returns the group rather than just its MIME type on purpose. A group can be unsupported while
 * reporting no sample type at all, and a decision that came back as a null *format* would read to
 * the caller as "nothing to report" - reintroducing the silence this exists to remove.
 *
 * ExoPlayer treats an undecodable track as one to *skip*, not as an error: it drops the track and
 * carries on playing whatever is left. For a stream with both, that means a black screen with the
 * audio playing and nothing at all in the log to say why - which is the worst failure mode there
 * is, because from the sofa it is indistinguishable from a crashed app.
 *
 * Observed for real: an episode served as `video/mp4v-es` (MPEG-4 part 2, the common old `.avi`
 * encode). The device's own decoder claimed the format and then died; the bundled software decoder
 * does not list `video/mp4v-es` at all, so the retry the player makes after a decoder failure
 * replaced a renderer that failed loudly with one that declines quietly. The track went unsupported
 * and the picture went black.
 *
 * Taking plain values rather than a `Tracks` keeps this testable without an ExoPlayer runtime, in
 * the same way [shouldRetryWithSoftwareVideo] does.
 */
internal fun unplayableVideoTrack(trackGroups: List<TrackSupport>): TrackSupport? =
    trackGroups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && !it.supported }

/**
 * What to say when the video is understood but undecodable.
 *
 * Named by format where the format is known, because "it will not play" sends people to check
 * their subscription, and the subscription is fine - the file is simply an encoding this television
 * has no decoder for. The `full` build's software decoders do not cover every old codec either,
 * so this is not a build-specific problem and must not be worded as one.
 */
internal fun unplayableVideoMessage(mimeType: String?): String {
    val format = when {
        mimeType == null -> "an unusual video format"
        mimeType.contains("mp4v", ignoreCase = true) ->
            "old-style MPEG-4 video, which most modern televisions cannot decode"
        mimeType.contains("mpeg2", ignoreCase = true) -> "MPEG-2 video"
        else -> mimeType
    }
    return "This episode is $format. There is sound but no picture, because no decoder on this " +
        "device can read it. The provider's copy is the problem, not your subscription."
}

/**
 * Whether a failure came from the video renderer rather than the audio one.
 *
 * Both raise the same error codes, so the code cannot tell them apart - only the renderer can. A
 * previous version of this matched on the exception message, which happened to work, but the
 * renderer name is a real field and does not depend on how a message is worded.
 *
 * Shared with the `full` flavor's software-video fallback rather than written twice: the two
 * answers have to agree, and a `main` copy the flavor cannot see is a copy that will drift.
 */
internal fun isVideoRendererError(error: PlaybackException): Boolean {
    val exo = error as? ExoPlaybackException ?: return false
    return exo.type == ExoPlaybackException.TYPE_RENDERER &&
        exo.rendererName?.contains("video", ignoreCase = true) == true
}

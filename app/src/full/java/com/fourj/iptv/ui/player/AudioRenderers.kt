package com.fourj.iptv.ui.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import java.util.ArrayList

/**
 * Renderer set for the `full` flavor: FFmpeg for audio *and* video, with hardware still preferred.
 *
 * **Audio.** Many providers carry AC-3 / E-AC-3 / DTS / MP2, which television hardware frequently
 * cannot decode. The symptom is a picture with no sound, indistinguishable from a dead stream, and
 * it is very common - a channel observed during testing advertised `audio/ac3 48000Hz 6ch` while the
 * device reported `supported=false`, so ExoPlayer refused the track and played a silent picture.
 *
 * **Video.** HEVC is near-universal in on-demand content and hardware support for it is patchy. A
 * real series episode on a real provider arrived as `video/x-matroska, video/hevc, 720p`, and
 * ExoPlayer reported `format_supported=YES` immediately before the device's decoder failed on it.
 *
 * **Why the decoders are attached here rather than via NextLib's `NextRenderersFactory`.** That
 * factory was observed never adding its audio renderer at all: it silently returns early unless the
 * output PCM format is non-zero, and logs nothing when it does. The FFmpeg library in this
 * dependency does contain the decoders, so they were capable and simply absent from the list.
 * Doing it here removes the dependency on the library's internal condition.
 *
 * Appending the video renderer is not sufficient on its own, and that is the whole subtlety:
 * ExoPlayer picks the first renderer that *claims* the format, and `MediaCodecVideoRenderer` claims
 * HEVC on devices that then fail to decode it. So the software renderer has to be put in front,
 * which is what [softwareVideoRenderersFactory] is for and what [isVideoDecodeFailure] triggers.
 */
@UnstableApi
internal fun audioRenderersFactory(context: Context): RenderersFactory =
    mediaRenderersFactory(context, preferSoftwareVideo = false)

/**
 * Renderer set for the `full` flavor with software video forced.
 *
 * Used only after a hardware video decoder has actually failed - see the note on
 * [audioRenderersFactory] for why appending is not enough.
 */
@UnstableApi
internal fun softwareVideoRenderersFactory(context: Context): RenderersFactory =
    mediaRenderersFactory(context, preferSoftwareVideo = true)

/** What the bundled FFmpeg can actually decode, for the log and for deciding a fallback. */
@UnstableApi
internal data class SoftwareDecodeSupport(
    val available: Boolean,
    val version: String?,
    val hevc: Boolean,
    val h264: Boolean,
    val ac3: Boolean,
    val eac3: Boolean,
) {
    @UnstableApi
    companion object {
        fun probe(): SoftwareDecodeSupport = runCatching {
            SoftwareDecodeSupport(
                available = FfmpegLibrary.isAvailable(),
                version = FfmpegLibrary.getVersion(),
                hevc = FfmpegLibrary.supportsFormat(HEVC_MIME),
                h264 = FfmpegLibrary.supportsFormat(H264_MIME),
                ac3 = FfmpegLibrary.supportsFormat(AC3_MIME),
                eac3 = FfmpegLibrary.supportsFormat(EAC3_MIME),
            )
        }.getOrElse {
            Log.w(TAG, "could not probe the software decoders", it)
            SoftwareDecodeSupport(false, null, false, false, false, false)
        }
    }
}

/** One log line saying what the bundled decoders can do, at player build time. */
@UnstableApi
internal fun logSoftwareDecodeSupport() {
    val support = SoftwareDecodeSupport.probe()
    Log.i(
        TAG,
        "software decoders: available=${support.available} version=${support.version} " +
            "hevc=${support.hevc} h264=${support.h264} " +
            "ac3=${support.ac3} eac3=${support.eac3}",
    )
}

/**
 * What to tell someone whose video failed, in a build that has already tried software decoding.
 *
 * By the time this is shown, the software decoder has been given the stream and failed too, so
 * saying the device "cannot decode" it would be wrong - it demonstrably decoded part of it. The
 * honest version names what was tried and leaves the next step open.
 */
internal fun videoDecodeFailureMessage(): String =
    "This video defeated both the device's own decoder and the software one. It is usually HEVC " +
        "at a resolution this device is too slow for."

/**
 * True when this failure is a video decoder giving up, so software video is worth a try.
 *
 * A thin wrapper over [shouldRetryWithSoftwareVideo] so the decision itself can be tested without
 * having to build an `ExoPlaybackException`, which needs a media period id and an Android runtime.
 */
@UnstableApi
internal fun isVideoDecodeFailure(error: PlaybackException): Boolean =
    shouldRetryWithSoftwareVideo(error.errorCode, isVideoRendererError(error))

/**
 * The decision, on plain values.
 *
 * The error code alone cannot tell video from audio - both raise the same decoder codes - so the
 * renderer is checked too, by the same [isVideoRendererError] the error message uses. Splitting
 * this into plain parameters keeps it testable without building an `ExoPlaybackException`, which
 * needs a media period id and an Android runtime.
 */
internal fun shouldRetryWithSoftwareVideo(
    errorCode: Int,
    isVideoRenderer: Boolean,
): Boolean = isVideoRenderer && errorCode in SOFTWARE_VIDEO_WORTH_TRYING

/**
 * Decoder failures worth retrying on the software decoder.
 *
 * `ERROR_CODE_DECODING_RESOURCES_RECLAIMED` is deliberately absent: it means the device took the
 * codec back under memory pressure, and rebuilding the player with different renderers would free
 * and re-allocate codecs in response to exactly the pressure that caused it. ExoPlayer recovers
 * from that one on its own.
 */
private val SOFTWARE_VIDEO_WORTH_TRYING = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

@UnstableApi
private fun mediaRenderersFactory(
    context: Context,
    preferSoftwareVideo: Boolean,
): RenderersFactory = object : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        outputAudioFormat: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableAudioTrackPlaybackParams: Boolean,
        audioSink: AudioSink,
        audioRendererEventHandler: Handler,
        audioRendererEventListener: AudioRendererEventListener,
        renderers: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            outputAudioFormat,
            mediaCodecSelector,
            enableAudioTrackPlaybackParams,
            audioSink,
            audioRendererEventHandler,
            audioRendererEventListener,
            renderers,
        )

        // Appended last, so ExoPlayer still prefers a hardware decoder and only falls back
        // to software when the device genuinely cannot handle the format.
        runCatching {
            renderers.add(
                FfmpegAudioRenderer(
                    audioRendererEventHandler,
                    audioRendererEventListener,
                    audioSink,
                ),
            )
        }.onFailure {
            // Never let this take the player down: without it the app still works, it just
            // goes silent on formats the hardware cannot decode.
            Log.w(TAG, "could not attach the software audio decoder", it)
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        outputVideoInputFormat: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        mediaCodecEventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        outputBufferTimeoutAfterReleaseMs: Long,
        renderers: ArrayList<Renderer>,
    ) {
        val buildHardware = {
            super.buildVideoRenderers(
                context,
                outputVideoInputFormat,
                mediaCodecSelector,
                enableDecoderFallback,
                mediaCodecEventHandler,
                videoRendererEventListener,
                outputBufferTimeoutAfterReleaseMs,
                renderers,
            )
        }

        if (preferSoftwareVideo) {
            // No hardware video renderer at all. This is the path for hardware that claimed the
            // format and then failed, and offering it first again would simply fail again.
            val added = runCatching {
                renderers.add(
                    FfmpegVideoRenderer(
                        outputBufferTimeoutAfterReleaseMs,
                        mediaCodecEventHandler,
                        videoRendererEventListener,
                        FFMPEG_VIDEO_MODE,
                    ),
                )
            }.isSuccess
            if (added) {
                Log.i(TAG, "software video decoder forced: using FFmpeg for video")
                return
            }
            Log.w(TAG, "could not force the software video decoder; keeping hardware")
            buildHardware()
            return
        }

        buildHardware()

        // Appended, so hardware still gets first refusal. Only helps when MediaCodec correctly
        // reports a format as unsupported; the "claims support then fails" case is handled by
        // rebuilding with software video forced.
        runCatching {
            renderers.add(
                FfmpegVideoRenderer(
                    outputBufferTimeoutAfterReleaseMs,
                    mediaCodecEventHandler,
                    videoRendererEventListener,
                    FFMPEG_VIDEO_MODE,
                ),
            )
        }.onFailure {
            Log.w(TAG, "could not attach the software video decoder", it)
        }
    }
}.setEnableDecoderFallback(true)

private const val HEVC_MIME = "video/hevc"
private const val H264_MIME = "video/avc"
private const val AC3_MIME = "audio/ac3"
private const val EAC3_MIME = "audio/eac3"
private const val TAG = "4J"

/**
 * The value NextLib itself passes when it builds this renderer.
 *
 * Copied rather than derived: the parameter has no name in the public signature, and the library's
 * own factory uses exactly this. Choosing a "sensible" value instead would be a guess about someone
 * else's decoder.
 */
private const val FFMPEG_VIDEO_MODE = 50

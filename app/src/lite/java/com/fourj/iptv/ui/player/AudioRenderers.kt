package com.fourj.iptv.ui.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Renderer set for the `lite` flavor.
 *
 * Stock ExoPlayer renderers - hardware decoding, no FFmpeg at all. Channels carrying AC-3 / DTS /
 * MP2 audio will be silent on a device that cannot decode them, and HEVC video may not play, which
 * is the trade-off that makes this APK several times smaller.
 *
 * The `full` flavor has its own copy of these functions that attaches the software decoders, and
 * can force software video after a hardware decoder has failed. Only one of the two flavors is
 * ever compiled into a given build.
 */
internal fun audioRenderersFactory(context: Context): RenderersFactory =
    stock(context)

/** Software video is not available in this flavor, so a fallback is not something it can offer. */
internal fun softwareVideoRenderersFactory(context: Context): RenderersFactory = stock(context)

private fun stock(context: Context): RenderersFactory =
    DefaultRenderersFactory(context)
        // If the preferred decoder for a format fails at runtime, try the others before giving
        // up on the channel. Cheap insurance on a device whose codec support is unknown.
        .setEnableDecoderFallback(true)

/**
 * What to tell someone whose video failed, in a build with no software video decoder.
 *
 * Still worth naming the other build, because it is a real difference: the `full` build has a
 * software video decoder and will at least try this stream. What it will not do is fix silent
 * audio, so that message is not reused here.
 */
internal fun videoDecodeFailureMessage(): String =
    "This device cannot decode this video, often HEVC. The 'full' build of the app adds a " +
        "software video decoder, which may manage it."

/**
 * Whether a video decoder failure is worth retrying with software.
 *
 * Always false here: this flavor ships no software decoders, so retrying would fail identically and
 * only delay the error the viewer needs to see.
 */
internal fun isVideoDecodeFailure(error: PlaybackException): Boolean = false

/** Nothing to report: there are no bundled decoders in this build. */
internal fun logSoftwareDecodeSupport() = Unit

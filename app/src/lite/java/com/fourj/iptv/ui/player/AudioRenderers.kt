package com.fourj.iptv.ui.player

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Renderer set for the `lite` flavor.
 *
 * Stock ExoPlayer renderers - hardware decoding, no FFmpeg. Channels carrying AC-3 / DTS / MP2
 * audio will be silent on a device that cannot decode them, which is the trade-off that makes
 * this APK several times smaller.
 *
 * The `full` flavor has its own copy of this function that attaches the software decoders. Only
 * one of the two is ever compiled into a given build.
 */
internal fun audioRenderersFactory(context: Context): RenderersFactory =
    DefaultRenderersFactory(context)
        // If the preferred decoder for a format fails at runtime, try the others before giving
        // up on the channel. Cheap insurance on a device whose codec support is unknown.
        .setEnableDecoderFallback(true)

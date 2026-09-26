package com.fourj.iptv.ui.player

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.RenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Renderer set for the `full` flavor.
 *
 * Many IPTV providers carry audio in AC-3 / E-AC-3 / DTS / MP2, which low-end television hardware
 * frequently cannot decode. The symptom is a picture with no sound, which is indistinguishable
 * from a dead stream and sends people looking for a network fault that does not exist.
 *
 * [NextRenderersFactory] extends ExoPlayer's own `DefaultRenderersFactory` and overrides the
 * audio/video renderer builders to *add* the FFmpeg decoders. Hardware decoding is still tried
 * first, so this widens the set of channels that work rather than forcing software decode on
 * everything.
 *
 * Only the `full` flavor compiles this file, which is what keeps `lite` free of the dependency
 * rather than merely not using it.
 */
internal fun audioRenderersFactory(context: Context): RenderersFactory {
    Log.i(TAG, "full flavor: software audio decoders present, version=${ffmpegVersion()}")
    // No .build() call: Media3 1.5+ dropped RenderersFactory#build() in favour of
    // createRenderers(...), and ExoPlayer.Builder takes the factory itself.
    return NextRenderersFactory(context)
}

private fun ffmpegVersion(): String = runCatching {
    io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary.getVersion()
}.getOrNull() ?: "unavailable"

private const val TAG = "4J"

package com.fourj.iptv.ui.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.util.ArrayList

/**
 * Renderer set for the `full` flavor.
 *
 * Many IPTV providers carry audio in AC-3 / E-AC-3 / DTS / MP2, which low-end television hardware
 * frequently cannot decode. The symptom is a picture with no sound - indistinguishable from a dead
 * stream - and it is very common: a channel observed during testing advertised
 * `audio/ac3 48000Hz 6ch` and the device reported `supported=false`, so ExoPlayer refused the
 * track and played a silent picture.
 *
 * **This attaches the decoder itself rather than using NextLib's `NextRenderersFactory`.** That
 * factory was observed never adding its renderer at all: it silently returns early unless the
 * output PCM format is non-zero, and it logged nothing when it did. The FFmpeg library here does
 * contain the AC-3 decoder (`libavcodec.so` carries the ac3/eac3 symbols), so the decoder was
 * capable and simply absent from the list. Doing it here removes that dependency on the library's
 * internal condition.
 *
 * The renderer is **appended**, so hardware MediaCodec still gets first refusal. This widens the
 * set of channels that produce sound; it does not force software decode on everything.
 */
internal fun audioRenderersFactory(context: Context): RenderersFactory =
    object : DefaultRenderersFactory(context) {

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
                Log.i(
                    TAG,
                    "software audio decoder attached: available=${FfmpegLibrary.isAvailable()} " +
                        "version=${FfmpegLibrary.getVersion()} " +
                        "ac3=${FfmpegLibrary.supportsFormat(AC3_MIME)} " +
                        "eac3=${FfmpegLibrary.supportsFormat(EAC3_MIME)}",
                )
            }.onFailure {
                // Never let this take the player down: without it the app still works, it just
                // goes silent on formats the hardware cannot decode.
                Log.w(TAG, "could not attach the software audio decoder", it)
            }
        }
    }.setEnableDecoderFallback(true)

private const val AC3_MIME = "audio/ac3"
private const val EAC3_MIME = "audio/eac3"
private const val TAG = "4J"

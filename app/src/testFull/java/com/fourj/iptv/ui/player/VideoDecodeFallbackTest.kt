package com.fourj.iptv.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the decision to retry a stream on the software video decoder.
 *
 * This exists because the first version of the check silently never returned true. It matched on
 * the exception message but was handed `errorCodeName`, which does not contain the renderer name,
 * so the fallback was dead code. It looked fine because the software decoder was also appended to
 * the renderer list, and appending is a real - just insufficient - behaviour.
 *
 * The test cases are taken from what the device actually did, not invented: a real HEVC episode
 * (`video/x-matroska, video/hevc, hvc1.1.6.L93.90`, 1099x720) that `c2.goldfish.hevc.decoder`
 * refused at `MediaCodec.native_configure`, and a real AAC channel that played with no trouble.
 */
class VideoDecodeFallbackTest {

    private val renderer = ExoPlaybackException.TYPE_RENDERER

    @Test
    fun `retries when the hardware video decoder fails to initialise`() {
        assertTrue(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                exceptionType = renderer,
                rendererName = "MediaCodecVideoRenderer",
            ),
        )
    }

    @Test
    fun `retries when the hardware video decoder fails mid-stream`() {
        assertTrue(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                exceptionType = renderer,
                rendererName = "MediaCodecVideoRenderer",
            ),
        )
    }

    @Test
    fun `retries when the format exceeds what the device claims it can do`() {
        // The shape that motivated all of this: the device advertised HEVC support, ExoPlayer
        // logged format_supported=YES, and the decoder then refused the stream outright.
        assertTrue(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                exceptionType = renderer,
                rendererName = "MediaCodecVideoRenderer",
            ),
        )
    }

    @Test
    fun `does not retry an audio decoder failure on the video decoder`() {
        // Same error codes as video. Only the renderer tells them apart, and getting this wrong
        // would swap a working picture for a working soundtrack.
        assertFalse(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                exceptionType = renderer,
                rendererName = "MediaCodecAudioRenderer",
            ),
        )
    }

    @Test
    fun `does not retry when the codec was reclaimed under memory pressure`() {
        // Rebuilding here would release and re-allocate codecs in response to the very pressure
        // that caused it. ExoPlayer recovers from this one by itself.
        assertFalse(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
                exceptionType = renderer,
                rendererName = "MediaCodecVideoRenderer",
            ),
        )
    }

    @Test
    fun `does not retry a network failure`() {
        assertFalse(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                exceptionType = ExoPlaybackException.TYPE_SOURCE,
                rendererName = null,
            ),
        )
    }

    @Test
    fun `does not retry when there is no renderer to attribute the failure to`() {
        assertFalse(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                exceptionType = renderer,
                rendererName = null,
            ),
        )
    }

    @Test
    fun `would also retry the software decoder's own failure, which the call site bounds`() {
        // True by design: the function cannot know it has already been tried. `triedSoftwareVideo`
        // in the player is what stops this becoming a loop, and it is the reason that flag exists.
        assertTrue(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                exceptionType = renderer,
                rendererName = "FfmpegVideoRenderer",
            ),
        )
    }
}

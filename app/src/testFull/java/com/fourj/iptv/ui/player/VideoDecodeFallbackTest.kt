package com.fourj.iptv.ui.player

import androidx.media3.common.PlaybackException
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

    @Test
    fun `retries when the hardware video decoder fails to initialise`() {
        assertTrue(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                isVideoRenderer = true,
            ),
        )
    }

    @Test
    fun `retries when the hardware video decoder fails mid-stream`() {
        assertTrue(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                isVideoRenderer = true,
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
                isVideoRenderer = true,
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
                isVideoRenderer = false,
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
                isVideoRenderer = true,
            ),
        )
    }

    @Test
    fun `does not retry a network failure`() {
        assertFalse(
            shouldRetryWithSoftwareVideo(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                isVideoRenderer = false,
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
                isVideoRenderer = true,
            ),
        )
    }
}

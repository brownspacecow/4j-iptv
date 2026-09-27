package com.fourj.iptv.ui.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a video the device cannot decode is reported rather than played as a black screen.
 *
 * The failure this covers is silent, which is what makes it dangerous. ExoPlayer drops a track that
 * no renderer supports instead of raising an error, so an episode in a format the television has no
 * decoder for plays its audio, advances the position, and shows nothing - and from the sofa that is
 * indistinguishable from a hung app. Observed on a real provider episode served as `video/mp4v-es`
 * in an `.avi` container: the position ran to 0:44 of an 11:04 film with no picture at any point.
 */
class UnplayableVideoTest {

    private fun video(supported: Boolean, mime: String? = "video/avc") =
        TrackSupport(C.TRACK_TYPE_VIDEO, supported, mime)

    private fun audio(supported: Boolean = true, mime: String? = "audio/mp4a-latm") =
        TrackSupport(C.TRACK_TYPE_AUDIO, supported, mime)

    @Test
    fun `an unsupported video track is found`() {
        val groups = listOf(
            audio(),
            TrackSupport(C.TRACK_TYPE_VIDEO, false, "video/mp4v-es"),
        )

        assertEquals("video/mp4v-es", unplayableVideoTrack(groups)?.sampleMimeType)
    }

    @Test
    fun `a decodable video track is not a problem`() {
        assertNull(unplayableVideoTrack(listOf(video(true, "video/avc"), audio())))
    }

    /**
     * A radio channel has no video track at all, which is normal and must not be reported as a
     * missing picture - otherwise every audio-only live channel raises an error on open.
     */
    @Test
    fun `no video track is not a problem`() {
        assertNull(unplayableVideoTrack(listOf(audio())))
        assertNull(unplayableVideoTrack(emptyList()))
    }

    /**
     * An unsupported *audio* track is a different problem, with its own message, already handled by
     * the decoder-failure path. Claiming the picture is missing because the sound is would be wrong.
     */
    @Test
    fun `an unsupported audio track is not blamed on the video`() {
        assertNull(unplayableVideoTrack(listOf(audio(false), video(true))))
    }

    /**
     * The case that actually happened, and a permanent one: `video/mp4v-es` is MPEG-4 part 2, which
     * the bundled software decoder's supported list does not include, so retrying with software
     * video cannot rescue it and the app has to say so rather than leave a black screen.
     */
    @Test
    fun `the observed format is recognised as undecodable`() {
        assertEquals(
            "video/mp4v-es",
            unplayableVideoTrack(
                listOf(TrackSupport(C.TRACK_TYPE_VIDEO, false, "video/mp4v-es"), audio()),
            )?.sampleMimeType,
        )
    }

    /**
     * A group can be unsupported while reporting no sample type. The decision has to survive that,
     * because a decision that came back as a null *format* would read to the caller as "nothing to
     * report" and put the silence straight back.
     */
    @Test
    fun `an unsupported video with no mime type is still reported`() {
        val found = unplayableVideoTrack(listOf(TrackSupport(C.TRACK_TYPE_VIDEO, false, null)))

        assertTrue("was skipped instead of reported", found != null)
        assertNull("expected no format to name", found?.sampleMimeType)
        assertTrue(
            "message unusable without a format: ${unplayableVideoMessage(found?.sampleMimeType)}",
            unplayableVideoMessage(found?.sampleMimeType).contains("no picture"),
        )
    }

    @Test
    fun `the message names the format and does not blame the subscription`() {
        val message = unplayableVideoMessage("video/mp4v-es")

        assertTrue("did not name the format: $message", message.contains("MPEG-4"))
        assertTrue("did not admit there is no picture: $message", message.contains("no picture"))
        assertTrue(
            "blames the viewer's subscription: $message",
            message.contains("not your subscription"),
        )
        // The whole point of naming the format is to say *why* this particular file is a problem:
        // without the device-limitation half, "MPEG-4 video" on its own reads as a broken file and
        // sends the viewer off to complain to the provider about something they cannot fix.
        assertTrue(
            "does not explain that it is a device limitation: $message",
            message.contains("cannot decode"),
        )
    }

    /**
     * A null MIME type is reachable, and the wording still has to make sense rather than saying
     * "This episode is null".
     */
    @Test
    fun `an unknown format still reads as a sentence`() {
        val message = unplayableVideoMessage(null)

        assertTrue("reads badly: $message", message.contains("an unusual video format"))
        assertTrue("has a stray null: $message", !message.contains("null"))
    }

    @Test
    fun `an unrecognised format falls back to naming the mime type`() {
        assertTrue(
            unplayableVideoMessage("video/x-some-codec").contains("video/x-some-codec"),
        )
    }
}

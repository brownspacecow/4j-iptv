package com.fourj.iptv.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That a dead live stream is noticed, recovered the right way, and that healthy playback is never
 * mistaken for a fault.
 *
 * The failure being guarded against was observed on a real channel: it played normally, the provider
 * closed the connection, and the app left the last frame on screen forever - no error, no buffering,
 * no CPU, and nothing the viewer could do about it.
 */
class StallWatchTest {

    private var now = 0L
    private val watch = StallWatch(
        stallAfterMs = 15_000,
        bufferingAfterMs = 45_000,
        minRestartGapMs = 10_000,
    )

    private fun playing(positionMs: Long, advanceMs: Long = 2_000): LiveFault {
        now += advanceMs
        return watch.observe(Player.STATE_READY, playWhenReady = true, positionMs, now)
    }

    private fun ended(advanceMs: Long = 2_000): LiveFault {
        now += advanceMs
        return watch.observe(Player.STATE_ENDED, playWhenReady = true, positionMs = 39_702, nowMs = now)
    }

    private fun buffering(advanceMs: Long = 2_000): LiveFault {
        now += advanceMs
        return watch.observe(Player.STATE_BUFFERING, playWhenReady = true, positionMs = 0, nowMs = now)
    }

    // ---------------------------------------------------------------------------------------------
    // The failure that was actually observed.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a live stream that ends is reported as an ended source`() {
        // The device sequence: playing normally, then the provider closes the connection.
        assertEquals(LiveFault.NONE, playing(9_515))
        assertEquals(LiveFault.NONE, playing(19_535))
        assertEquals(LiveFault.NONE, playing(39_691))

        // A television channel does not finish, so this is a dead source and not an ending.
        assertEquals(LiveFault.SOURCE_ENDED, ended())
    }

    /**
     * The distinction the caller acts on, and the reason this enum exists.
     *
     * An ended source can be reopened in place, which keeps the audio track alive - and this provider
     * ends a stream every ten to thirty seconds, so that path runs constantly. A wedged stream is
     * still mid-flight and has to be torn down. Reporting both as the same thing would mean
     * recreating the audio track every half minute, which is audible.
     */
    @Test
    fun `an ended source is distinguished from a stream that stopped mid-flight`() {
        assertEquals(LiveFault.SOURCE_ENDED, ended())

        val fresh = StallWatch(stallAfterMs = 15_000, bufferingAfterMs = 45_000, minRestartGapMs = 10_000)
        var nowLocal = 0L
        fun tick(position: Long, advanceMs: Long = 2_000): LiveFault {
            nowLocal += advanceMs
            return fresh.observe(Player.STATE_READY, playWhenReady = true, position, nowLocal)
        }
        assertEquals(LiveFault.NONE, tick(0))
        assertEquals(LiveFault.NONE, tick(30_000))
        // Long enough past the last movement to cross the 15s stall threshold.
        assertEquals(LiveFault.NONE, tick(30_000, advanceMs = 10_000))
        assertEquals(LiveFault.NO_PROGRESS, tick(30_000, advanceMs = 6_000))
    }

    @Test
    fun `an ended stream is reported straight away rather than after a timeout`() {
        // It is not a stall, it is an end, and waiting out a stall timer would leave the frozen
        // frame sitting there for another fifteen seconds for no reason.
        assertEquals(LiveFault.SOURCE_ENDED, ended(advanceMs = 0))
    }

    @Test
    fun `a channel that ends the instant it is reopened is not hammered`() {
        // The provider can be genuinely broken. Without a floor between restarts this would re-request
        // the stream as fast as the poll runs, which is a busy loop and rude to the panel.
        assertEquals(LiveFault.SOURCE_ENDED, ended())
        assertEquals("immediate retry suppressed", LiveFault.NONE, ended())
        assertEquals("still inside the floor", LiveFault.NONE, ended(advanceMs = 4_000))

        // Once the floor has passed, trying again is right - the channel may have come back.
        assertEquals(LiveFault.SOURCE_ENDED, ended(advanceMs = 7_000))
    }

    @Test
    fun `a reopened stream is judged on its own merits`() {
        assertEquals(LiveFault.SOURCE_ENDED, ended())
        now += 12_000
        assertEquals(LiveFault.NONE, playing(1_000))
        assertEquals(LiveFault.NONE, playing(3_000))
        assertEquals(LiveFault.NONE, playing(5_000))
    }

    // ---------------------------------------------------------------------------------------------
    // A genuine wedge: state says playing, nothing moves.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a stream that stops advancing while still ready is reported`() {
        assertEquals("first sample only records", LiveFault.NONE, playing(0))
        assertEquals(LiveFault.NONE, playing(30_000))
        assertEquals(LiveFault.NONE, playing(30_000, advanceMs = 10_000))
        assertEquals(LiveFault.NO_PROGRESS, playing(30_000, advanceMs = 6_000))
    }

    @Test
    fun `a fault is reported once, not on every poll`() {
        assertEquals(LiveFault.NONE, playing(0))
        assertEquals(LiveFault.NONE, playing(30_000))
        assertEquals(LiveFault.NO_PROGRESS, playing(30_000, advanceMs = 20_000))
        assertEquals("must not re-report immediately", LiveFault.NONE, playing(30_000, advanceMs = 1_000))
    }

    @Test
    fun `healthy playback is never reported`() {
        for (i in 1..300) {
            assertEquals("a working stream was called a fault at $i", LiveFault.NONE, playing(i * 10_000L))
        }
    }

    @Test
    fun `slow but genuine progress is not reported`() {
        // A low-bitrate channel whose position creeps. Restarting a working-but-slow stream would be
        // worse than the fault it is meant to catch.
        assertEquals(LiveFault.NONE, playing(0))
        for (i in 1..40) {
            assertEquals("slow progress wrongly called a fault at $i", LiveFault.NONE, playing(i * 100L))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // States that are not faults.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a pause on purpose is not a fault`() {
        playing(0)
        now += 20_000
        assertEquals(
            "paused playback must never be called a fault",
            LiveFault.NONE,
            watch.observe(Player.STATE_READY, playWhenReady = false, positionMs = 0, nowMs = now),
        )
        assertEquals("and no stale timer fires on resume", LiveFault.NONE, playing(0))
    }

    @Test
    fun `a channel that is slow to start is not a fault`() {
        // Buffering before the first frame is normal, even on a slow panel.
        assertEquals(LiveFault.NONE, buffering())
        assertEquals("20s of buffering is ordinary for a cold channel", LiveFault.NONE, buffering(advanceMs = 20_000))
        assertEquals(LiveFault.NONE, playing(1_000))
        assertEquals(LiveFault.NONE, playing(3_000))
    }

    @Test
    fun `a stream stuck buffering forever is reported as no progress`() {
        // The first report is the one that matters; after it the restart floor suppresses the rest,
        // so this records whether *any* poll saw the fault rather than what the last one returned.
        var reported = LiveFault.NONE
        repeat(40) {
            val fault = buffering()
            if (fault != LiveFault.NONE) reported = fault
        }
        assertEquals(LiveFault.NO_PROGRESS, reported)
    }

    @Test
    fun `a player idling between stop and prepare is not a fault`() {
        // That is a normal transient on every channel change, so judging it would mean restarting a
        // viewer who is zapping, in a loop.
        playing(0)
        now += 30_000
        assertEquals(
            "idle is a transient, not a fault",
            LiveFault.NONE,
            watch.observe(Player.STATE_IDLE, true, 30_000, now),
        )
    }
}

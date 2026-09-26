package com.fourj.iptv.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a dead live stream is noticed and restarted, and that healthy playback is not mistaken for a
 * fault.
 *
 * The failure being guarded against was observed on a real channel: it played normally for about
 * forty seconds, the source ended, and the app left the last frame on screen forever - no error, no
 * buffering, no CPU, and nothing the viewer could do about it.
 */
class StallWatchTest {

    private var now = 0L
    private val watch = StallWatch(
        stallAfterMs = 15_000,
        bufferingAfterMs = 45_000,
        minRestartGapMs = 10_000,
    )

    private fun playing(positionMs: Long, advanceMs: Long = 2_000): Boolean {
        now += advanceMs
        return watch.observe(Player.STATE_READY, playWhenReady = true, positionMs, now)
    }

    private fun ended(advanceMs: Long = 2_000): Boolean {
        now += advanceMs
        return watch.observe(Player.STATE_ENDED, playWhenReady = true, positionMs = 39_702, nowMs = now)
    }

    // ---------------------------------------------------------------------------------------------
    // The failure that was actually observed.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a live stream that ends is reported`() {
        // The device sequence, near enough: playing normally, then the source simply stops.
        assertFalse(playing(9_515))
        assertFalse(playing(19_535))
        assertFalse(playing(39_691))

        // STATE_ENDED. A television channel does not finish, so this is a dead source, not an end.
        assertTrue("a live stream ending must be reported", ended())
    }

    @Test
    fun `an ended stream is reported straight away rather than after a timeout`() {
        // It is not a stall, it is an end, and waiting out a stall timer would leave the frozen
        // frame sitting there for another fifteen seconds for no reason.
        assertTrue(ended(advanceMs = 0))
    }

    @Test
    fun `a channel that ends the instant it is restarted is not hammered`() {
        // The provider can be genuinely broken. Without a floor between restarts this would re-request
        // the stream as fast as the poll runs, which is a busy loop and rude to the panel.
        assertTrue("first attempt goes ahead", ended())
        assertFalse("immediate retry is suppressed", ended())
        assertFalse("still inside the floor", ended(advanceMs = 4_000))

        // Once the floor has passed, trying again is right - the channel may have come back.
        assertTrue("retried after the floor", ended(advanceMs = 7_000))
    }

    @Test
    fun `a recovered stream is not reported again straight away`() {
        assertTrue(ended())
        // Reopened, and playing: the new source has its own end to reach.
        now += 12_000
        assertFalse(playing(1_000))
        assertFalse(playing(3_000))
        assertFalse(playing(5_000))
    }

    // ---------------------------------------------------------------------------------------------
    // A genuine wedge: state says playing, nothing moves.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a stream that stops advancing while still ready is reported`() {
        assertFalse("first sample only records", playing(0))
        assertFalse(playing(30_000))
        assertFalse(playing(30_000, advanceMs = 10_000))
        assertTrue("a frozen position must be reported", playing(30_000, advanceMs = 6_000))
    }

    @Test
    fun `a stall is reported once, not on every poll`() {
        assertFalse(playing(0))
        assertFalse(playing(30_000))
        assertTrue("first report", playing(30_000, advanceMs = 20_000))
        assertFalse("must not re-report immediately", playing(30_000, advanceMs = 1_000))
    }

    @Test
    fun `healthy playback is never reported`() {
        var reported = false
        for (i in 1..300) {
            if (playing(i * 10_000L)) reported = true
        }
        assertFalse("a working stream was called a fault", reported)
    }

    @Test
    fun `slow but genuine progress is not reported`() {
        // A low-bitrate channel whose position creeps. Restarting a working-but-slow stream would be
        // worse than the fault it is meant to catch.
        assertFalse(playing(0))
        for (i in 1..40) {
            assertFalse("slow progress wrongly called a fault at step $i", playing(i * 100L))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // States that are not faults.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a pause on purpose is not a fault`() {
        playing(0)
        now += 20_000
        assertFalse(
            "paused playback must never be called a fault",
            watch.observe(Player.STATE_READY, playWhenReady = false, positionMs = 0, nowMs = now),
        )
        assertFalse("and no stale timer fires on resume", playing(0))
    }

    @Test
    fun `a channel that is slow to start is not a fault`() {
        now += 2_000
        assertFalse(watch.observe(Player.STATE_BUFFERING, true, 0, now))
        now += 20_000
        assertFalse(
            "20s of buffering is ordinary for a cold channel",
            watch.observe(Player.STATE_BUFFERING, true, 0, now),
        )
        now += 2_000
        assertFalse(watch.observe(Player.STATE_READY, true, 1_000, now))
        assertFalse(watch.observe(Player.STATE_READY, true, 3_000, now))
    }

    @Test
    fun `a stream stuck buffering forever is reported`() {
        var reported = false
        for (i in 1..40) {
            now += 2_000
            if (watch.observe(Player.STATE_BUFFERING, true, 0, now)) reported = true
        }
        assertTrue("a stream stuck buffering must be reported", reported)
    }

    @Test
    fun `a player idling between stop and prepare is not a fault`() {
        // That is a normal transient on every channel change, so judging it would mean restarting a
        // zapping viewer in a loop.
        playing(0)
        now += 30_000
        assertFalse(
            "idle is a transient, not a fault",
            watch.observe(Player.STATE_IDLE, true, 30_000, now),
        )
    }
}

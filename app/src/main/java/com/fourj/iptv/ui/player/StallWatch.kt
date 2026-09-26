package com.fourj.iptv.ui.player

import androidx.media3.common.Player

/**
 * Notices when a **live** stream has stopped being watchable, so playback can be restarted.
 *
 * Built from an observed failure on the real provider, and the first version of this was wrong in an
 * instructive way.
 *
 * What actually happens: a live channel plays perfectly for thirty to forty seconds - position
 * advancing at the right rate, video moving - and then the source simply **ends**. ExoPlayer moves
 * to [Player.STATE_ENDED] and, with nothing to play, holds the last decoded frame on screen
 * indefinitely. The picture is frozen, no error is raised, the app uses no CPU, and from the
 * viewer's side the channel has died for no visible reason. Zapping away and back always fixed it,
 * which is what pointed at the session rather than the origin.
 *
 * So there are two separate things to catch, and only one of them is a "stall":
 *
 *  - **The stream ended.** For live TV this is never legitimate. A television channel does not
 *    finish. This is the failure that was actually observed, and treating it as a normal end - which
 *    is what the first attempt did - is precisely what left a frozen frame on screen forever.
 *  - **The stream is playing but going nowhere.** A genuine wedge: state stays READY while the
 *    position stops advancing.
 *
 * Position is the right signal for both, and it is worth being clear why after getting this wrong
 * the first time. An earlier attempt counted *rendered frames* instead, on the reasoning that
 * ExoPlayer advances the position from the clock and so might keep reporting progress while the
 * picture was frozen. That reasoning was sound but the premise was untested, and measurement killed
 * it: the per-frame analytics callback never fired at all on this stream, so the counter sat at its
 * "no frames yet" sentinel and could not have detected anything. Had it fired it would also have
 * been the wrong choice for the failure that actually occurs. Position plus playback state covers
 * both cases with one number.
 *
 * No timeouts would have fixed this either. A read timeout only fires while a read is in flight, and
 * by the time a source has *ended* there is no read to time out - the player had already been told,
 * politely, that the stream was over.
 */
internal class StallWatch(
    /** How long a playing stream may go without advancing before it counts as stalled. */
    private val stallAfterMs: Long = STALL_AFTER_MS,
    /** How long a stream may sit buffering before it counts as stuck. */
    private val bufferingAfterMs: Long = BUFFERING_AFTER_MS,
    /**
     * Floor between restarts.
     *
     * A channel whose source dies instantly would otherwise be re-requested as fast as the poll
     * runs, which is both a busy loop and a rude way to treat a provider. Backing off keeps a
     * genuinely dead channel from being hammered while still recovering a slow one promptly.
     */
    private val minRestartGapMs: Long = MIN_RESTART_GAP_MS,
) {
    private var lastPositionMs = NO_POSITION
    private var lastProgressAtMs = 0L
    private var bufferingSinceMs: Long? = null
    private var lastRestartAtMs = Long.MIN_VALUE

    /**
     * Whether the stream needs restarting. Reports once, then resets.
     *
     * One-shot matters: the caller restarts playback, and a watcher that kept firing would restart
     * the stream in a tight loop.
     */
    fun observe(state: Int, playWhenReady: Boolean, positionMs: Long, nowMs: Long): Boolean {
        // Paused on purpose is not a fault. This app never pauses live TV, but a player can be
        // paused while the activity is backgrounded, and restarting it then would fight the user.
        if (!playWhenReady) {
            reset()
            return false
        }

        if (tooSoonToRestart(nowMs)) return false

        // A live stream that has ended is the failure this whole class exists for. A television
        // channel does not finish, so the source dropped and the only useful response is to open it
        // again and pick up a new live edge.
        if (state == Player.STATE_ENDED) {
            return report(nowMs)
        }

        // Buffering before the first frame is normal, even on a slow panel, so it gets a patient
        // timer rather than being called a stall at once.
        if (state == Player.STATE_BUFFERING) {
            val since = bufferingSinceMs
            if (since == null) {
                bufferingSinceMs = nowMs
                return false
            }
            if (nowMs - since < bufferingAfterMs) return false
            return report(nowMs)
        }
        bufferingSinceMs = null

        // Idle is what a player is briefly between stop() and prepare(), so it is not judged.
        if (state != Player.STATE_READY) {
            lastPositionMs = NO_POSITION
            return false
        }

        if (positionMs != lastPositionMs) {
            lastPositionMs = positionMs
            lastProgressAtMs = nowMs
            return false
        }

        // The first reading after a restart has nothing to compare against, so it is recorded
        // rather than counted - however long the previous stall lasted.
        if (lastProgressAtMs == 0L) {
            lastProgressAtMs = nowMs
            return false
        }

        if (nowMs - lastProgressAtMs < stallAfterMs) return false
        return report(nowMs)
    }

    private fun tooSoonToRestart(nowMs: Long): Boolean =
        lastRestartAtMs != Long.MIN_VALUE && nowMs - lastRestartAtMs < minRestartGapMs

    private fun report(nowMs: Long): Boolean {
        lastRestartAtMs = nowMs
        reset()
        return true
    }

    private fun reset() {
        lastPositionMs = NO_POSITION
        lastProgressAtMs = 0L
        bufferingSinceMs = null
    }

    companion object {
        const val STALL_AFTER_MS = 15_000L
        const val BUFFERING_AFTER_MS = 45_000L
        const val MIN_RESTART_GAP_MS = 10_000L

        /**
         * How often the player is sampled. Short enough that a fault is noticed while the viewer is
         * still watching, long enough that the poll is free.
         */
        const val POLL_INTERVAL_MS = 2_000L

        private const val NO_POSITION = Long.MIN_VALUE
    }
}

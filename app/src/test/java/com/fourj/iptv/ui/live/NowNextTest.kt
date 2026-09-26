package com.fourj.iptv.ui.live

import com.fourj.iptv.domain.model.EpgListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowNextTest {

    private val base = 1_700_000_000L

    private fun listing(
        id: String,
        streamId: Int = 1,
        start: Long,
        end: Long,
        nowPlaying: Boolean = false,
    ) = EpgListing(
        id = id,
        streamId = streamId,
        title = "Programme $id",
        start = start,
        end = end,
        description = null,
        channelId = null,
        nowPlaying = nowPlaying,
    )

    @Test
    fun `finds the programme spanning the clock`() {
        val now = base + 3_600
        val guide = listOf(
            listing("a", start = base, end = base + 3_000),
            listing("b", start = base + 3_000, end = base + 7_000),
            listing("c", start = base + 7_000, end = base + 11_000),
        )
        val result = guide.toNowNext(now)
        assertEquals("b", result.getValue(1).current.id)
        assertEquals("c", result.getValue(1).next?.id)
    }

    @Test
    fun `resolves current by time rather than trusting the now_playing flag`() {
        // Panels do send a now_playing flag, and panels do get it wrong. Time is authoritative.
        val now = base + 3_600
        val guide = listOf(
            listing("wrong", start = base, end = base + 3_000, nowPlaying = true),
            listing("right", start = base + 3_000, end = base + 7_000, nowPlaying = false),
        )
        assertEquals("right", guide.toNowNext(now).getValue(1).current.id)
    }

    @Test
    fun `falls back to the flagged programme when nothing spans the clock`() {
        // Happens when the guide has not caught up, or a programme overruns its slot.
        val now = base + 99_999
        val guide = listOf(listing("only", start = base, end = base + 1_000, nowPlaying = true))
        assertEquals("only", guide.toNowNext(now).getValue(1).current.id)
    }

    @Test
    fun `handles out of order listings`() {
        val now = base + 3_600
        val guide = listOf(
            listing("later", start = base + 7_000, end = base + 11_000),
            listing("current", start = base + 3_000, end = base + 7_000),
            listing("earlier", start = base, end = base + 3_000),
        )
        val result = guide.toNowNext(now).getValue(1)
        assertEquals("current", result.current.id)
        assertEquals("later", result.next?.id)
    }

    @Test
    fun `separates channels`() {
        val now = base + 3_600
        val guide = listOf(
            listing("a1", streamId = 1, start = base, end = base + 7_000),
            listing("b1", streamId = 2, start = base, end = base + 7_000),
        )
        val result = guide.toNowNext(now)
        assertEquals(setOf(1, 2), result.keys)
        assertEquals("a1", result.getValue(1).current.id)
        assertEquals("b1", result.getValue(2).current.id)
    }

    @Test
    fun `a channel with nothing on now is absent rather than blank`() {
        val now = base + 3_600
        val guide = listOf(listing("a1", start = base, end = base + 1_000))
        assertTrue(guide.toNowNext(now).isEmpty())
    }

    @Test
    fun `a guide with no next programme reports null rather than the same one`() {
        val now = base + 3_600
        val guide = listOf(listing("only", start = base, end = base + 7_000))
        val result = guide.toNowNext(now).getValue(1)
        assertNull(result.next)
    }

    @Test
    fun `progress runs from zero to one across the programme`() {
        val nowNext = NowNext(
            current = listing("a", start = base, end = base + 1_000),
            next = null,
        )
        assertEquals(0f, nowNow(nextNow = nowNext, at = base), 0.001f)
        assertEquals(0.5f, nowNow(nextNow = nowNext, at = base + 500), 0.001f)
        assertEquals(1f, nowNow(nextNow = nowNext, at = base + 1_000), 0.001f)
    }

    @Test
    fun `progress is clamped outside the programme and tolerates a zero-length slot`() {
        val normal = NowNext(listing("a", start = base, end = base + 1_000), null)
        assertEquals(0f, normal.progress(base - 5_000), 0.001f)
        assertEquals(1f, normal.progress(base + 9_000), 0.001f)

        // A provider reporting start == end must not divide by zero.
        val degenerate = NowNext(listing("a", start = base, end = base), null)
        assertEquals(0f, degenerate.progress(base), 0.001f)
    }

    @Test
    fun `minutes remaining never goes negative`() {
        val nowNext = NowNext(listing("a", start = base, end = base + 60), null)
        assertEquals(1L, nowNext.endsInMinutes(base))
        assertEquals(0L, nowNext.endsInMinutes(base + 600))
    }

    private fun nowNow(nextNow: NowNext, at: Long) = nextNow.progress(at)
}

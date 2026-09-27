package com.fourj.iptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "cached copy from ..." label.
 *
 * Worth pinning because the wording is a promise. The cache does not expire on a timer - the panel
 * offers no way to know what changed - so this line is the only thing telling the viewer how old
 * what they are looking at actually is. Getting the units wrong, or calling a week-old shelf
 * "yesterday", makes an indefinitely-cached catalog look maintained when it is not.
 */
class CacheAgeTest {

    private val now = 1_700_000_000_000L
    private fun minutesAgo(minutes: Long) = describeCacheAge(now - minutes * 60_000, now)

    @Test
    fun `under a minute reads as moments ago`() {
        assertEquals("moments ago", minutesAgo(0))
        assertEquals("moments ago", describeCacheAge(now - 30_000, now))
    }

    @Test
    fun `minutes are counted and pluralised`() {
        assertEquals("1 minute ago", minutesAgo(1))
        assertEquals("5 minutes ago", minutesAgo(5))
        assertEquals("59 minutes ago", minutesAgo(59))
    }

    @Test
    fun `hours take over from minutes at sixty`() {
        assertEquals("1 hour ago", minutesAgo(60))
        assertEquals("3 hours ago", minutesAgo(180))
    }

    @Test
    fun `days take over from hours at twenty four`() {
        assertEquals("1 day ago", minutesAgo(60 * 24))
        assertEquals("6 days ago", minutesAgo(60 * 24 * 6))
    }

    @Test
    fun `past a week it stops claiming precision`() {
        // Beyond a week the exact number stops being useful and starts implying a freshness the app
        // cannot vouch for. "over a week ago" is both true and the honest amount of detail.
        assertEquals("over a week ago", minutesAgo(60 * 24 * 7))
        assertEquals("over a week ago", minutesAgo(60 * 24 * 90))
    }

    @Test
    fun `a clock that has gone backwards does not produce a negative age`() {
        // Device clocks are adjusted. A shelf "fetched" in the future must not render as a negative
        // number of minutes ago, which is what a naive subtraction gives.
        assertEquals("moments ago", describeCacheAge(now + 60_000, now))
    }
}

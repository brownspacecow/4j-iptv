package com.fourj.iptv.data.local

import com.fourj.iptv.domain.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the age shown next to the city.
 *
 * There is no cache window any more - the lookup runs on every launch - so the age is not deciding
 * anything. It is only ever *shown*, and that is the point of testing it. A request can fail, and
 * when it does the last good answer stays on screen; the age is the only thing that tells the
 * viewer the city they are reading came from last week rather than from this launch. Getting it
 * wrong is invisible until someone reads a stale location as a current one.
 */
class PlaceStoreAgeTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L
    private val place = Place("Kansas City", "Missouri", "United States", "US")

    private fun stored(fetchedAt: Long) = Stored(place, fetchedAt)

    @Test
    fun `age counts whole days`() {
        assertEquals(0L, stored(now).ageInDays(now))
        assertEquals(0L, stored(now - day + 1).ageInDays(now))
        assertEquals(1L, stored(now - day).ageInDays(now))
        assertEquals(3L, stored(now - 3 * day).ageInDays(now))
    }

    @Test
    fun `a clock that has gone backwards reads as zero, not as a negative age`() {
        // Timezone corrections and NTP steps on a cheap television do move the clock, so a record can
        // be stamped in what is now the future. "-1 days ago" on a television is worse than "0 days".
        assertEquals(0L, stored(now + 5 * day).ageInDays(now))
        assertEquals(0L, stored(now + 1).ageInDays(now))
    }

    @Test
    fun `nothing cached has no age`() {
        // Null rather than zero: there is no lookup to be zero days old, and rendering "0 days ago"
        // next to no city at all would be a claim about something that never happened.
        assertNull((null as Stored?).ageInDays(now))
    }
}

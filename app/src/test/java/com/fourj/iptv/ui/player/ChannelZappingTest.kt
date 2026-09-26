package com.fourj.iptv.ui.player

import com.fourj.iptv.data.remote.xtream.LiveStreamDto
import com.fourj.iptv.data.repository.toEntity
import com.fourj.iptv.domain.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelZappingTest {

    private fun channel(id: Int) = LiveChannel(
        streamId = id,
        name = "Channel $id",
        categoryId = null,
        iconUrl = null,
        containerExtension = null,
        directSource = null,
        httpUserAgent = null,
        httpReferrer = null,
        hasArchive = false,
        archiveDurationDays = null,
    )

    @Test
    fun `moves down and up the list`() {
        val channels = listOf(channel(1), channel(2), channel(3))
        assertEquals(2, zapped(channels, channels[0], 1)?.streamId)
        assertEquals(1, zapped(channels, channels[1], -1)?.streamId)
    }

    @Test
    fun `wraps past the last channel back to the first`() {
        val channels = listOf(channel(1), channel(2), channel(3))
        assertEquals(1, zapped(channels, channels[2], 1)?.streamId)
    }

    @Test
    fun `wraps before the first channel back to the last`() {
        val channels = listOf(channel(1), channel(2), channel(3))
        assertEquals(3, zapped(channels, channels[0], -1)?.streamId)
    }

    @Test
    fun `a large step still lands inside the list`() {
        // The historical bug this guards: a naive `index + delta` goes out of bounds and crashes
        // the player when someone holds the down key. Kotlin's `mod` is the floored remainder,
        // so a negative delta wraps forward rather than producing a negative index.
        val channels = listOf(channel(1), channel(2), channel(3))
        assertEquals(2, zapped(channels, channels[0], 100)?.streamId)   // 100 mod 3 == 1
        assertEquals(3, zapped(channels, channels[0], -100)?.streamId) // -100 mod 3 == 2
    }

    @Test
    fun `a whole number of laps returns to the same channel`() {
        val channels = listOf(channel(1), channel(2), channel(3))
        assertEquals(1, zapped(channels, channels[0], 99)?.streamId) // 99 mod 3 == 0
        assertEquals(1, zapped(channels, channels[0], -99)?.streamId)
    }

    @Test
    fun `an empty list yields nothing`() {
        assertNull(zapped(emptyList(), channel(1), 1))
    }

    @Test
    fun `a channel outside the list yields nothing`() {
        val channels = listOf(channel(1), channel(2))
        assertNull(zapped(channels, channel(99), 1))
    }
}

class LiveStreamMappingTest {

    @Test
    fun `a well formed stream maps through`() {
        val entity = LiveStreamDto(
            name = "  BBC One  ",
            streamId = 7,
            containerExtension = "ts",
            tvArchive = 1,
            tvArchiveDuration = 3,
        ).toEntity(sortOrder = 5)

        assertEquals(7, entity?.streamId)
        assertEquals("BBC One", entity?.name)
        assertEquals("ts", entity?.containerExtension)
        assertTrue(entity!!.hasArchive)
        assertEquals(3, entity.archiveDurationDays)
        assertEquals(5, entity.sortOrder)
    }

    @Test
    fun `blank optional strings become null rather than empty`() {
        // Empty strings in the cache mean "no logo" everywhere else, not "a logo of ''".
        val entity = LiveStreamDto(
            name = "Channel",
            streamId = 1,
            streamIcon = "   ",
            httpUserAgent = "",
            httpReferrer = "",
            categoryId = "  ",
        ).toEntity(sortOrder = 0)

        assertNull(entity?.iconUrl)
        assertNull(entity?.httpUserAgent)
        assertNull(entity?.httpReferrer)
        assertNull(entity?.categoryId)
    }

    @Test
    fun `a stream with no id or no name is dropped`() {
        assertNull(LiveStreamDto(name = "Channel", streamId = 0).toEntity(0))
        assertNull(LiveStreamDto(name = "   ", streamId = 5).toEntity(0))
    }

    @Test
    fun `tv archive is only true when the panel says one`() {
        assertFalse(LiveStreamDto(name = "c", streamId = 1, tvArchive = 0).toEntity(0)!!.hasArchive)
        assertFalse(LiveStreamDto(name = "c", streamId = 1, tvArchive = null).toEntity(0)!!.hasArchive)
    }
}

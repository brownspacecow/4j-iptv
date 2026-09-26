package com.fourj.iptv.data.remote

import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.domain.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAddressTest {

    @Test
    fun `bare host gets an http scheme`() {
        assertEquals("http://panel.example.com", ProviderAddress.parseServer("panel.example.com"))
    }

    @Test
    fun `host and port are preserved`() {
        assertEquals("http://panel.example.com:8080", ProviderAddress.parseServer("panel.example.com:8080"))
    }

    @Test
    fun `explicit scheme is kept`() {
        assertEquals("https://panel.example.com", ProviderAddress.parseServer("https://panel.example.com"))
    }

    @Test
    fun `a portal path is stripped from the server address`() {
        // Users paste the whole link into the server box often enough that this has to work.
        assertEquals(
            "http://panel.example.com:8080",
            ProviderAddress.parseServer("http://panel.example.com:8080/get.php?username=a&password=b"),
        )
    }

    @Test
    fun `trailing slash is removed`() {
        assertEquals("http://panel.example.com", ProviderAddress.parseServer("http://panel.example.com/"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("http://panel.example.com", ProviderAddress.parseServer("  http://panel.example.com  "))
    }

    @Test
    fun `unsupported scheme is rejected`() {
        assertNull(ProviderAddress.parseServer("ftp://panel.example.com"))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(ProviderAddress.parseServer("not a url"))
        assertNull(ProviderAddress.parseServer(""))
    }

    @Test
    fun `get php link yields a full profile`() {
        val profile = ProviderAddress.parseProfile(
            "http://panel.example.com:8080/get.php?username=alice&password=s3cret&type=m3u_plus",
        )
        assertEquals("http://panel.example.com:8080", profile?.baseUrl)
        assertEquals("alice", profile?.username)
        assertEquals("s3cret", profile?.password)
    }

    @Test
    fun `player api link yields a full profile and ignores extra parameters`() {
        val profile = ProviderAddress.parseProfile(
            "https://panel.example.com/player_api.php?username=alice&password=s3cret&action=get_live_streams",
        )
        assertEquals("https://panel.example.com", profile?.baseUrl)
        assertEquals("alice", profile?.username)
        assertEquals("s3cret", profile?.password)
    }

    @Test
    fun `percent encoded credentials are decoded`() {
        val profile = ProviderAddress.parseProfile(
            "http://panel.example.com:8080/get.php?username=al%40ice.com&password=p%40ss%26word",
        )
        assertEquals("al@ice.com", profile?.username)
        assertEquals("p@ss&word", profile?.password)
    }

    @Test
    fun `a server address with no credentials is not a profile`() {
        // The three-field form has to be rejected here so the caller falls back to the typed
        // username and password rather than silently sending empty ones.
        assertNull(ProviderAddress.parseProfile("http://panel.example.com:8080"))
    }

    @Test
    fun `a link missing the password is not a profile`() {
        assertNull(ProviderAddress.parseProfile("http://panel.example.com/get.php?username=alice"))
    }

    @Test
    fun `cleartext usage is detected`() {
        assertTrue(ProviderProfile("http://a.com", "u", "p").usesCleartext)
        assertTrue(!ProviderProfile("https://a.com", "u", "p").usesCleartext)
    }
}

class StreamUrlsTest {

    private val profile = ProviderProfile("http://panel.example.com:8080", "alice", "s3cret")

    private fun channel(
        streamId: Int = 42,
        containerExtension: String? = null,
        directSource: String? = null,
    ) = LiveChannel(
        streamId = streamId,
        name = "Test",
        categoryId = null,
        iconUrl = null,
        containerExtension = containerExtension,
        directSource = directSource,
        httpUserAgent = null,
        httpReferrer = null,
        hasArchive = false,
        archiveDurationDays = null,
    )

    @Test
    fun `builds the conventional live path`() {
        assertEquals(
            "http://panel.example.com:8080/live/alice/s3cret/42.ts",
            StreamUrls.liveStream(profile, channel()),
        )
    }

    @Test
    fun `uses the container extension when the panel declares one`() {
        assertEquals(
            "http://panel.example.com:8080/live/alice/s3cret/42.m3u8",
            StreamUrls.liveStream(profile, channel(containerExtension = "m3u8")),
        )
    }

    @Test
    fun `tolerates a leading dot and mixed case in the extension`() {
        assertEquals(
            "http://panel.example.com:8080/live/alice/s3cret/42.m3u8",
            StreamUrls.liveStream(profile, channel(containerExtension = ".M3U8")),
        )
    }

    @Test
    fun `direct source wins when the panel supplies one`() {
        assertEquals(
            "https://cdn.example.com/stream/42.m3u8",
            StreamUrls.liveStream(
                profile,
                channel(containerExtension = "ts", directSource = "https://cdn.example.com/stream/42.m3u8"),
            ),
        )
    }

    @Test
    fun `a relative direct source is ignored`() {
        // A relative value cannot be opened, so it must not shadow the known-good live path.
        assertEquals(
            "http://panel.example.com:8080/live/alice/s3cret/42.ts",
            StreamUrls.liveStream(profile, channel(directSource = "/local/42.ts")),
        )
    }

    @Test
    fun `playlist url targets m3u plus`() {
        val url = StreamUrls.playlist(profile)
        assertTrue(url.startsWith("http://panel.example.com:8080/get.php?"))
        assertTrue(url.contains("username=alice"))
        assertTrue(url.contains("password=s3cret"))
        assertTrue(url.contains("type=m3u_plus"))
    }
}

package com.fourj.iptv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * That a stream URL can be logged without logging the viewer's password.
 *
 * An Xtream stream URL carries the username and password as path segments, because that is the only
 * way the panel will serve the stream. Every diagnostic line that printed the URL was therefore
 * printing the password in cleartext - into logcat, which the viewer can read, routinely pastes into
 * bug reports, and on older Android any app holding READ_LOGS can read too.
 */
class RedactCredentialsTest {

    private val password = "s3cret"

    @Test
    fun `the password is gone from every kind of stream url`() {
        val urls = listOf(
            "http://panel.test:8080/live/alice/$password/42.ts",
            "http://panel.test:8080/movie/alice/$password/3185125.mp4",
            "http://panel.test:8080/series/alice/$password/3054675.mkv",
        )

        for (url in urls) {
            val redacted = redactCredentials(url)
            assertFalse("password survived redaction of $url", redacted.contains(password))
            assertFalse("username survived redaction of $url", redacted.contains("alice"))
        }
    }

    @Test
    fun `what makes the line useful is left in place`() {
        // Host, content type and stream id are what actually identify a failing stream, and none of
        // them is secret. A log line reading "<redacted>" teaches nobody anything.
        val redacted = redactCredentials("http://panel.test:8080/movie/alice/$password/3185125.mp4")

        assertEquals("http://panel.test:8080/movie/<user>/<pass>/3185125.mp4", redacted)
    }

    @Test
    fun `a url with no credentials is left exactly as it was`() {
        // Direct sources come from a CDN and have no credentials in them. Rewriting a url that has
        // nothing to hide would only make it harder to recognise in a log.
        val direct = "https://cdn.example.com/stream/42.m3u8"
        assertEquals(direct, redactCredentials(direct))
    }

    @Test
    fun `an unrecognised shape is left alone rather than mangled`() {
        for (url in listOf(
            "not a url at all",
            "http://panel.test:8080",
            "http://panel.test:8080/get.php?username=alice&password=$password&type=m3u_plus",
        )) {
            assertEquals(url, redactCredentials(url))
        }
    }

    @Test
    fun `redaction does not throw on odd input`() {
        // Not decoration: this runs against whatever a panel put in `direct_source`, which is not
        // a field the app controls. An exception here would take down playback over a log line.
        val odd = listOf("", "/", "://", "http://", "http://h/live", "http://h/live/only-one")
        for (url in odd) {
            assertEquals("odd input should pass through unchanged: '$url'", url, redactCredentials(url))
        }
    }
}

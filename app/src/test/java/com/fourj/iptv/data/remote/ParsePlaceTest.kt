package com.fourj.iptv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers turning an ipwho.is response into something worth putting on a television.
 *
 * The request is not tested - it is three lines of OkHttp and the emulator always resolves, because
 * the emulator's address is the host's. Everything that can actually be wrong is in the parsing, and
 * every one of these cases is something the service genuinely does.
 */
class ParsePlaceTest {

    @Test
    fun `reads a full answer`() {
        val place = parsePlace(
            """
            {"success":true,"city":"Kansas City","region":"Missouri",
             "country":"United States","country_code":"us"}
            """.trimIndent(),
        )
        assertEquals("Kansas City", place?.city)
        assertEquals("Missouri", place?.region)
        assertEquals("United States", place?.country)
        // Upper-cased so the flag or code is usable without a case check at the point of use.
        assertEquals("US", place?.countryCode)
    }

    @Test
    fun `a refusal is null even though the body is well formed`() {
        // The important case. This is what a private or reserved range gets, and it arrives as valid
        // JSON with HTTP 200 - so nothing about the transport says it failed.
        assertNull(parsePlace("""{"success":false,"message":"Reserved range"}"""))
    }

    @Test
    fun `a refusal is rejected on the flag alone`() {
        // This case exists because the obvious version of the test above does not discriminate. A
        // refusal carrying no city is turned away by the missing city whether or not the success
        // flag is checked, so removing that check entirely left the suite green - the guard was
        // untested. Giving the refusal a city forces the flag to be what rejects it.
        //
        // Whether the service ever sends this shape is unknown. That is the point: if it ever does,
        // the flag is the only thing standing between a refusal and a confident wrong city.
        assertNull(parsePlace("""{"success":false,"city":"Nowhere","country":"Atlantis"}"""))
    }

    @Test
    fun `a success with no city is null`() {
        // The service can answer without placing the address. Drawing "Kansas City, , " would be
        // worse than drawing nothing.
        assertNull(parsePlace("""{"success":true,"region":"Missouri","country":"United States"}"""))
        assertNull(parsePlace("""{"success":true,"city":"   "}"""))
    }

    @Test
    fun `a region identical to the city is dropped`() {
        // Common for city-states and for small districts, and "Malta, Malta" is noise.
        val place = parsePlace("""{"success":true,"city":"Malta","region":"Malta","country":"Malta"}""")
        assertEquals("Malta", place?.city)
        assertNull(place?.region)
        assertEquals("Malta, Malta", place?.label)
    }

    @Test
    fun `blank optional parts become null rather than empty strings`() {
        val place = parsePlace(
            """{"success":true,"city":"Berlin","region":"  ","country":"","country_code":""}""",
        )
        assertEquals("Berlin", place?.city)
        assertNull(place?.region)
        assertNull(place?.country)
        assertNull(place?.countryCode)
        // And the label is just the one part that exists, with no trailing separator.
        assertEquals("Berlin", place?.label)
    }

    @Test
    fun `unknown fields do not break it`() {
        // The service returns latitude, timezone, a flag object and a readme link. A strict decoder
        // would fail on every one of them.
        val place = parsePlace(
            """
            {"success":true,"city":"Leeds","region":"England","country":"United Kingdom",
             "country_code":"gb","latitude":53.8,"longitude":-1.5,
             "flag":{"emoji":"x","img":"https://example.test/f.svg"},
             "connection":{"asn":1,"isp":"x"},"readme":"https://example.test/docs"}
            """.trimIndent(),
        )
        assertEquals("Leeds, England, United Kingdom", place?.label)
    }

    @Test
    fun `malformed json is null rather than a crash`() {
        // Whatever the service does under load, the caller is a background coroutine that must not
        // take the app down.
        assertNull(parsePlace(""))
        assertNull(parsePlace("<html>503 Service Unavailable</html>"))
        assertNull(parsePlace("""{"success":true,"city":}"""))
    }
}

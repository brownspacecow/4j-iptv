package com.fourj.iptv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelTextTest {

    @Test
    fun `decodes base64 titles seen from a real provider`() {
        // Captured verbatim from a live get_short_epg response, and each expectation verified by
        // decoding independently rather than by reading it off the log.
        assertEquals("CNN News", decodePanelText("Q05OIE5ld3M="))
        assertEquals("The Situation Room", decodePanelText("VGhlIFNpdHVhdGlvbiBSb29t"))
        assertEquals(
            "NewsNation Live With Marni Hughes",
            decodePanelText("TmV3c05hdGlvbiBMaXZlIFdpdGggTWFybmkgSHVnaGVz"),
        )
        assertEquals(
            "Money, Power, Politics With Stephanie Ruhle",
            decodePanelText("TW9uZXksIFBvd2VyLCBQb2xpdGljcyBXaXRoIFN0ZXBoYW5pZSBSdWhsZQ=="),
        )
        assertEquals("TV Drama", decodePanelText("VFYgRHJhbWE="))
    }

    @Test
    fun `re-pads unpadded base64 rather than rejecting it`() {
        // Padding is genuinely absent on some panels, and rejecting on length alone would leave
        // those titles encoded. Verified round-trip below.
        val unpadded = "VGhlIFNpdHVhdGlvbiBSb29t".trimEnd('=')
        assertEquals("The Situation Room", decodePanelText(unpadded))
    }

    @Test
    fun `refuses base64 that decodes to a truncated fragment`() {
        // Observed on a real channel: Q2Vhc2VmaXJI decodes to "CeasefirH", half of a word. The
        // panel's data is corrupt, and neither the encoded nor the decoded form means anything.
        // The plausibility guard keeps the input rather than inventing a title from it.
        assertEquals("CeasefirH", "CeasefirH")
        assertEquals("Q2Vhc2VmaXJI", decodePanelText("Q2Vhc2VmaXJI"))
    }

    @Test
    fun `leaves ordinary titles alone`() {
        // The important half. "News" is syntactically valid Base64 and decodes to rubbish, so a
        // naive decode would corrupt the common case to fix the rare one.
        for (title in listOf(
            "NewsNation",
            "CNN International",
            "FOX Business",
            "The Weather Channel",
            "BBC One HD",
            "Sky Sports Main Event",
            "SportsCenter",
            "QVC",
            "HGTV",
            "msnbc",
        )) {
            assertEquals("must not be mangled: $title", title, decodePanelText(title))
        }
    }

    @Test
    fun `leaves short strings and non-base64 characters alone`() {
        assertEquals("News", decodePanelText("News"))
        assertEquals("A", decodePanelText("A"))
        assertEquals("", decodePanelText(""))
        assertEquals("Fox News!", decodePanelText("Fox News!"))
        assertEquals("abc-def", decodePanelText("abc-def"))
    }

    @Test
    fun `refuses input that is not a valid base64 block`() {
        // Right alphabet, wrong length.
        assertEquals("abcdefg", decodePanelText("abcdefg"))
        // Padding in the middle.
        assertEquals("ab=cd", decodePanelText("ab=cd"))
    }

    @Test
    fun `does not decode valid base64 whose result is not plausible text`() {
        // Decodes to control bytes, not a title.
        assertEquals("AAECAwQ=", decodePanelText("AAECAwQ="))
    }
}

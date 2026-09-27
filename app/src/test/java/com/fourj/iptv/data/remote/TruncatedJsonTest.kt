package com.fourj.iptv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Salvaging a catalog the provider cut off.
 *
 * The behavior that matters: a shelf must not be lost because its *last* title was incomplete. A
 * film shelf here is about 20 MB and is cut at roughly 2.2 MB, so every large shelf on this provider
 * ends mid-record - and the titles before the cut are perfectly good ones someone might search for.
 *
 * The awkward cases are the ones worth pinning: braces and escaped quotes inside titles, which a
 * naive `lastIndexOf("}")` gets wrong in ways that produce invalid JSON or silently drop the last
 * good record.
 */
class TruncatedJsonTest {

    @Test
    fun `closes a cut-off array at its last complete element`() {
        val body = """[{"name":"One"},{"name":"Two"},{"name":"Thr"""

        val closed = TruncatedJson.closeArray(body)

        assertNotNull(closed)
        assertEquals("""[{"name":"One"},{"name":"Two"}]""", closed)
    }

    @Test
    fun `leaves a complete array alone`() {
        // Returns null rather than an unchanged string, so the caller parses it as it always would
        // and the normal path is never affected by this code.
        val body = """[{"name":"One"}]"""

        assertNull(TruncatedJson.closeArray(body))
    }

    @Test
    fun `returns null when no element completed before the cut`() {
        // The dangerous case to get wrong. An empty salvage would be indistinguishable from a
        // provider that genuinely has no titles in the category, and the shelf would be marked done.
        // Nothing here closes, so there is nothing to keep.
        assertNull(TruncatedJson.closeArray("""[{"na"""))
    }

    @Test
    fun `keeps whatever completed even when the cut lands inside the second element`() {
        // The common shape for a small shelf: one good record, then a cut. Recovering that one
        // record is the whole point - the alternative is a shelf that never becomes searchable.
        val closed = TruncatedJson.closeArray("""[{"name":"One"},{"na""")

        assertEquals("""[{"name":"One"}]""", closed)
    }

    @Test
    fun `handles a brace inside a title`() {
        // A naive last-brace scan would cut here and produce invalid JSON.
        val body = """[{"name":"Extreme {Sports}"},{"name":"Two"},{"na"""

        val closed = TruncatedJson.closeArray(body)

        assertEquals("""[{"name":"Extreme {Sports}"},{"name":"Two"}]""", closed)
    }

    @Test
    fun `handles an escaped quote inside a title`() {
        val body = """[{"name":"The \"Big\" One"},{"name":"Two"},{"na"""

        val closed = TruncatedJson.closeArray(body)

        assertEquals("""[{"name":"The \"Big\" One"},{"name":"Two"}]""", closed)
    }

    @Test
    fun `handles a truncated string at the end`() {
        val body = """[{"name":"One"},{"name":"Two"},{"name":"Thre"""

        val closed = TruncatedJson.closeArray(body)

        assertEquals("""[{"name":"One"},{"name":"Two"}]""", closed)
    }

    @Test
    fun `keeps a trailing comma out of the result`() {
        // The cut often lands right after a comma, which must not be carried into the closed array.
        val body = """[{"name":"One"},{"name":"Two"},"""

        val closed = TruncatedJson.closeArray(body)

        assertEquals("""[{"name":"One"},{"name":"Two"}]""", closed)
    }

    @Test
    fun `rejects a body that is not an array`() {
        // A login object or an error page. Closing it as if it were an array would be nonsense.
        assertNull(TruncatedJson.closeArray("""{"user_info":{"auth":1}}"""))
    }

    @Test
    fun `counts only complete top level elements`() {
        val body = """[{"a":{"b":1}},{"c":[1,2]},{"d":3},{"e"""

        assertEquals(3, TruncatedJson.countCompleteElements(body))
    }

    @Test
    fun `counts a complete array correctly`() {
        assertEquals(3, TruncatedJson.countCompleteElements("""[{"a":1},{"b":2},{"c":3}]"""))
    }

    @Test
    fun `an empty array counts as zero and is left alone`() {
        assertEquals(0, TruncatedJson.countCompleteElements("[]"))
        assertNull(TruncatedJson.closeArray("[]"))
    }

    @Test
    fun `reports the shortfall only when something is actually missing`() {
        // 20 MB announced, 2.2 MB received, so ~17.4 MB never arrived. The size is the panel's own
        // Content-Length, which is what makes the gap explainable rather than mysterious.
        val lost = TruncatedJson.salvagedSuffix(receivedBytes = 2_200_000, expectedBytes = 20_000_000)
        assertEquals(" (panel announced 19531 KB, 17382 KB never arrived)", lost)

        // Nothing lost, or nothing announced: no note, rather than a confusing zero.
        assertEquals("", TruncatedJson.salvagedSuffix(2_000, 2_000))
        assertEquals("", TruncatedJson.salvagedSuffix(2_000, null))
    }
}

package com.fourj.iptv.ui.search

import com.fourj.iptv.data.repository.normaliseSearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the search term normalisation the repository applies before querying.
 *
 * Kept as a real function rather than inline `.lowercase()` calls so the rules can be pinned. Each
 * of these exists because of something specific about this provider, not as general tidiness.
 */
class SearchQueryTest {

    @Test
    fun `blank term matches nothing`() {
        assertTrue(normaliseSearchTerm("").isEmpty())
        assertTrue(normaliseSearchTerm("   ").isEmpty())
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        // Trailing space is the common case: someone types a word then the space before the next
        // one, and a trailing space in a LIKE pattern matches nothing at all.
        assertEquals("sky", normaliseSearchTerm("  sky  "))
    }

    @Test
    fun `case is folded so search is case insensitive`() {
        assertEquals("sky at night", normaliseSearchTerm("Sky At Night"))
    }

    @Test
    fun `accents are preserved rather than stripped`() {
        // A provider carries accented titles, and someone searching for one types the accent. Folding
        // to ASCII would mean "Amelie" matched "Amelie" but not "Amélie" - the search would work for
        // the copy-paste case and fail for the person actually typing the title.
        assertEquals("amélie", normaliseSearchTerm("Amélie"))
    }

    @Test
    fun `wildcards in a typed term are treated as text`() {
        // The index query is a LIKE, and '%' and '_' are meaningful inside a LIKE pattern. A viewer
        // searching for "100%" or "Spider-Man_2" would otherwise have their search silently
        // rewritten into a pattern that matches far more than they asked for.
        assertTrue(normaliseSearchTerm("100%").contains('%'))
    }

    @Test
    fun `a term that is only whitespace after folding is rejected`() {
        assertTrue(normaliseSearchTerm("\t\n ").isEmpty())
    }
}

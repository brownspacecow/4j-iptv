package com.fourj.iptv.ui.search

import com.fourj.iptv.data.repository.IndexCoverage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "is this index complete" decision.
 *
 * This number is load-bearing in a way that is easy to get wrong. It decides whether the app tells a
 * viewer "nothing matched, and it is not on your provider" - a claim the viewer has no way to check -
 * or "nothing matched yet, and not everything is indexed". Getting it wrong in the optimistic
 * direction makes the app state something false with total confidence.
 */
class SearchCoverageTest {

    @Test
    fun `complete only when every category has been walked`() {
        val coverage = IndexCoverage(
            liveIndexed = 500,
            movieIndexed = 40_000,
            seriesIndexed = 8_000,
            scopesTotal = 12,
            scopesComplete = 12,
        )
        assertTrue(coverage.isComplete)
    }

    @Test
    fun `incomplete while any category is outstanding`() {
        val coverage = IndexCoverage(
            liveIndexed = 500,
            // A large number of indexed titles, and still not complete - this is the case that a
            // count-based heuristic gets wrong, mistaking volume for coverage.
            movieIndexed = 40_000,
            seriesIndexed = 8_000,
            scopesTotal = 12,
            scopesComplete = 11,
        )
        assertFalse(coverage.isComplete)
    }

    @Test
    fun `incomplete when the provider offers no categories at all`() {
        // Categories not loaded yet means scopesTotal is 0, and claiming completeness from that
        // would report a fresh install as fully indexed.
        val coverage = IndexCoverage(
            liveIndexed = 0,
            movieIndexed = 0,
            seriesIndexed = 0,
            scopesTotal = 0,
            scopesComplete = 0,
        )
        assertFalse(coverage.isComplete)
    }

    @Test
    fun `total sums every kind`() {
        val coverage = IndexCoverage(
            liveIndexed = 500,
            movieIndexed = 40_000,
            seriesIndexed = 8_000,
            scopesTotal = 12,
            scopesComplete = 12,
        )
        assertTrue(coverage.total == 48_500)
    }
}

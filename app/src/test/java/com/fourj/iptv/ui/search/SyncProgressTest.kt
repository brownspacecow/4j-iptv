package com.fourj.iptv.ui.search

import com.fourj.iptv.data.repository.IndexCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers the sync screen puts in front of someone.
 *
 * Every one of these is a claim about what the app knows. Getting them wrong is not a cosmetic
 * problem: "everything is synced" when a third of the catalogue was never read is the app telling a
 * viewer a falsehood with total confidence, and a search that finds nothing is indistinguishable
 * from one that has not been run.
 */
class SyncProgressTest {

    @Test
    fun `an unknown total reports no fraction rather than a finished one`() {
        // Before the first run, nothing is known about how many shelves exist. A bar at 0% would
        // read as "just started"; a bar at 100% would read as "done". Neither is knowable, so the
        // screen shows a caption instead of a bar.
        val progress = SyncProgress(liveDone = 0, liveTotal = 0)
        assertEquals(null, progress.fraction)
    }

    @Test
    fun `fraction is the shelves done over the shelves offered`() {
        val progress = SyncProgress(
            liveDone = 6,
            liveTotal = 6,
            movieDone = 20,
            movieTotal = 100,
            seriesDone = 10,
            seriesTotal = 100,
        )
        assertEquals(36f / 206f, progress.fraction!!, 0.0001f)
    }

    @Test
    fun `fraction stays within bounds when more is done than was expected`() {
        // A shelf can be added at the provider between the list being fetched and the run finishing.
        // Clamping keeps the bar honest instead of drawing past its own end.
        val progress = SyncProgress(liveDone = 12, liveTotal = 10)
        assertEquals(1f, progress.fraction!!, 0.0001f)
    }

    @Test
    fun `shelves sum across the three kinds`() {
        val progress = SyncProgress(
            liveDone = 6, liveTotal = 6,
            movieDone = 20, movieTotal = 100,
            seriesDone = 10, seriesTotal = 100,
        )
        assertEquals(36, progress.shelvesDone)
        assertEquals(206, progress.shelvesTotal)
    }

    @Test
    fun `a fresh sync is not complete`() {
        // The state a fresh install is in after pressing sync on a provider whose category list
        // has never been fetched. Nothing is indexed, so it must not read as done.
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
    fun `complete means every shelf was walked, not merely that titles exist`() {
        // The distinction that caught the "spongebob" bug's cousin: 9,000 titles indexed while
        // shelves are outstanding means those titles are from the shelves that worked, and everything
        // else is still missing.
        val partial = IndexCoverage(
            liveIndexed = 9_000,
            movieIndexed = 0,
            seriesIndexed = 0,
            scopesTotal = 269,
            scopesComplete = 12,
        )
        assertFalse(partial.isComplete)
        assertEquals(9_000, partial.total)
    }

    @Test
    fun `a fully walked index is complete`() {
        val coverage = IndexCoverage(
            liveIndexed = 10_000,
            movieIndexed = 40_000,
            seriesIndexed = 8_000,
            scopesTotal = 269,
            scopesComplete = 269,
        )
        assertTrue(coverage.isComplete)
        assertEquals(58_000, coverage.total)
    }
}

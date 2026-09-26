package com.fourj.iptv.data.repository

import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.LiveCategory
import com.fourj.iptv.domain.model.VodCategory
import com.fourj.iptv.domain.model.VodShelf
import com.fourj.iptv.testing.PanelFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The background indexer, against a stand-in panel.
 *
 * The test that matters is [a shelf that cannot be read does not stop the run]. The provider
 * truncates large JSON responses constantly, and the first version let that end the whole job: the
 * run died at whichever shelf happened to be oversized, having indexed a couple of thousand
 * channels out of a few hundred shelves, and no amount of pressing the button again would ever
 * finish it. The fix was to skip and carry on.
 */
@RunWith(RobolectricTestRunner::class)
class SearchIndexingTest {

    private val panel = PanelFixture()
    private val repository = panel.searchRepository()

    @After
    fun tearDown() = panel.close()

    @Test
    fun `a shelf that cannot be read does not stop the run`() = runBlocking {
        enqueueChannels("USA One HD", "USA Two HD")
        // Once per retry attempt - see PanelFixture.enqueueTimes.
        panel.enqueueTimes(3, """[{"num":1,"name":"Broken"""")
        enqueueChannels("USA Three HD")

        val run = repository.indexEverything(
            liveCategories = listOf(
                LiveCategory("1", "First"),
                LiveCategory("2", "Too big"),
                LiveCategory("3", "Third"),
            ),
            vodShelves = emptyList(),
        )

        // The third shelf is still reached, which is the whole point.
        assertEquals(3, run.added)
        assertEquals(listOf("LIVE_CHANNEL:2"), run.skippedScopes)
    }

    @Test
    fun `a completed shelf is not fetched again on a second run`() = runBlocking {
        enqueueChannels("USA One HD")
        repository.indexEverything(listOf(LiveCategory("1", "First")), emptyList())

        val second = repository.indexEverything(listOf(LiveCategory("1", "First")), emptyList())

        // Resumability, and the reason progress is recorded per category: a second run must not
        // re-download shelves that are already done, or "index everything" would restart from zero
        // every time it was pressed.
        assertEquals(0, second.added)
        assertEquals(1, panel.recordedRequests().size)
    }

    @Test
    fun `a failed shelf is retried on the next run rather than remembered as done`() = runBlocking {
        panel.enqueueTimes(3, """[{"num":1,"name":"Broken"""")
        val first = repository.indexEverything(listOf(LiveCategory("1", "First")), emptyList())
        assertEquals(listOf("LIVE_CHANNEL:1"), first.skippedScopes)

        enqueueChannels("USA One HD")
        val second = repository.indexEverything(listOf(LiveCategory("1", "First")), emptyList())

        // The counterpart to the test above. Marking a failure complete would make the gap permanent;
        // leaving it unrecorded means a later run - possibly minutes later, when the provider is
        // less busy - can still pick it up.
        assertEquals(1, second.added)
        assertTrue(second.skippedScopes.isEmpty())
    }

    @Test
    fun `a film category shorter than one page is marked finished`() = runBlocking {
        panel.enqueue("""[{"stream_id":1,"name":"A Film","container_extension":"mp4"}]""")

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.MOVIE, VodCategory("570", "Movies-New Releases"))),
        )

        assertEquals(1, run.added)
        assertTrue(run.skippedScopes.isEmpty())
    }

    @Test
    fun `a series shelf is walked with the series endpoint`() = runBlocking {
        panel.enqueue("""[{"series_id":7,"name":"A Show"}]""")

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.SERIES, VodCategory("419", "Series-Drama"))),
        )

        assertEquals(1, run.added)
        val request = panel.recordedRequests()
        assertTrue(
            "expected get_series, got $request",
            request.any { it.contains("action=get_series") },
        )
    }

    /**
     * The kind comes from the database, never from the category's text.
     *
     * This test exists because the opposite was true and shipped. The kind used to be re-derived by
     * testing the category id for a "Series" prefix - and this panel's `category_id` is numeric
     * (570, 401, 419) while "Series" appears only in the *name*. Every check was therefore false,
     * every series shelf was paged with `get_vod_streams`, which returns nothing for a series
     * category, and no series was ever indexed. Searching "spongebob" found live TV and nothing
     * else, and the test that should have caught it passed the *name* in as the id.
     *
     * The ids below are deliberately numeric and unremarkable, matching what the panel sends.
     */
    @Test
    fun `a numeric series category id is still walked as a series`() = runBlocking {
        panel.enqueue("""[{"series_id":9,"name":"SpongeBob SquarePants"}]""")

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.SERIES, VodCategory("419", "Series-Kids"))),
        )

        assertEquals(1, run.added)
        val paths = panel.recordedRequests()
        assertTrue("expected get_series, got $paths", paths.any { it.contains("action=get_series") })
        assertTrue(
            "a series shelf must never be paged as films: $paths",
            paths.none { it.contains("action=get_vod_streams") },
        )
    }

    @Test
    fun `a truncated shelf is partly indexed rather than skipped`() = runBlocking {
        // The panel cuts a large response off partway through the array. Before, that failed the
        // whole call and the shelf was skipped - losing every film that had arrived intact, which
        // is most of a 20 MB shelf. It should now be a partial success.
        val truncated = """
            [{"stream_id":1,"name":"SpongeBob SquarePants","category_id":"10"},
             {"stream_id":2,"name":"SpongeBob Slightly Squidward","category_id":"10"},
             {"stream_id":3,"name":"The Incredi
        """.trimIndent()
        panel.enqueue(truncated)

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.MOVIE, VodCategory("570", "Movies-Kids"))),
        )

        // Two complete titles salvaged out of three; the third never finished arriving.
        assertEquals(2, run.added)
        assertTrue(
            "a truncated shelf must not count as skipped: ${run.skippedScopes}",
            run.skippedScopes.isEmpty(),
        )
        assertEquals(2, panel.searchIndexDao().search("spongebob", 10).size)
    }

    @Test
    fun `a shelf truncated before any title arrived is still skipped`() = runBlocking {
        // The opposite case, and the reason the salvage is not unconditional. Nothing completed, so
        // there is nothing to keep - and reporting that as an indexed shelf would leave it looking
        // like a provider with no films in it.
        panel.enqueue("""[{"stream_id":1,"na""")

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.MOVIE, VodCategory("570", "Movies-Kids"))),
        )

        assertEquals(0, run.added)
        assertEquals(listOf("MOVIE:570"), run.skippedScopes)
    }

    private fun enqueueChannels(vararg names: String) {
        val rows = names.joinToString(",") { """{"num":1,"name":"$it","stream_id":1,"category_id":"1"}""" }
        panel.enqueue("[$rows]")
    }
}

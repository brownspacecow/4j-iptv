package com.fourj.iptv.data.repository

import androidx.room.Room
import com.fourj.iptv.data.local.SearchIndexDatabase
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.LiveCategory
import com.fourj.iptv.domain.model.ProviderProfile
import com.fourj.iptv.domain.model.VodCategory
import com.fourj.iptv.domain.model.VodShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

    private lateinit var server: MockWebServer
    private lateinit var database: SearchIndexDatabase
    private lateinit var repository: SearchRepository
    private lateinit var profile: ProviderProfile

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            SearchIndexDatabase::class.java,
        ).allowMainThreadQueries().build()

        profile = ProviderProfile(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret",
        )
        repository = SearchRepository(
            profile = profile,
            database = database,
            liveApi = XtreamNetwork.createApi(profile, debugLogging = false),
            vodApi = XtreamNetwork.createVodApi(profile),
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun `a shelf that cannot be read does not stop the run`() = runBlocking {
        enqueueChannels("USA One HD", "USA Two HD")
        // Enqueued once per retry attempt, not once. The retry helper makes three requests before
        // giving up, each consuming a response from the queue - enqueue a single broken body and the
        // following shelves are served truncated JSON instead of their own, which turns this test
        // into a measurement of the mock rather than of the code.
        enqueueTruncated()
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
        enqueueChannels("USA One HD", "USA Two HD")
        repository.indexEverything(listOf(LiveCategory("1", "First")), emptyList())

        val second = repository.indexEverything(listOf(LiveCategory("1", "First")), emptyList())

        // Resumability, and the reason progress is recorded per category: a second run must not
        // re-download shelves that are already done, or "index everything" would restart from zero
        // every time it was pressed.
        assertEquals(0, second.added)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failed shelf is retried on the next run rather than remembered as done`() = runBlocking {
        enqueueTruncated()
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
        enqueue("""[{"stream_id":1,"name":"A Film","container_extension":"mp4"}]""")

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.MOVIE, VodCategory("570", "Movies-New Releases"))),
        )

        assertEquals(1, run.added)
        assertTrue(run.skippedScopes.isEmpty())
    }

    @Test
    fun `a series shelf is walked with the series endpoint`() = runBlocking {
        enqueue("""[{"series_id":7,"name":"A Show"}]""")

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.SERIES, VodCategory("419", "Series-Drama"))),
        )

        assertEquals(1, run.added)
        val request = server.takeRequest()
        assertTrue(
            "expected get_series, got ${request.path}",
            request.path!!.contains("action=get_series"),
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
        enqueue("""[{"series_id":9,"name":"SpongeBob SquarePants"}]""")

        val run = repository.indexEverything(
            liveCategories = emptyList(),
            vodShelves = listOf(VodShelf(ContentKind.SERIES, VodCategory("419", "Series-Kids"))),
        )

        assertEquals(1, run.added)
        val paths = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .map { it.path.orEmpty() }
            .toList()
        assertTrue("expected get_series, got $paths", paths.any { it.contains("action=get_series") })
        assertTrue(
            "a series shelf must never be paged as films: $paths",
            paths.none { it.contains("action=get_vod_streams") },
        )
    }

    /**
     * A response cut off mid-array, which is what this provider does to a large payload.
     *
     * Enqueued once per retry attempt: the retry helper issues three requests before giving up, and
     * each consumes a response from the queue.
     */
    private fun enqueueTruncated() {
        repeat(3) { enqueue("""[{"num":1,"name":"Broken""") }
    }

    private fun enqueueChannels(vararg names: String) {
        val rows = names.joinToString(",") { """{"num":1,"name":"$it","stream_id":1,"category_id":"1"}""" }
        enqueue("[$rows]")
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }
}

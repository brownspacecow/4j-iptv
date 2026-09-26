package com.fourj.iptv.data.repository

import androidx.room.Room
import com.fourj.iptv.data.local.VodDatabase
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.ProviderProfile
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
 * That a film or series shelf is downloaded once, not on every visit.
 *
 * The provider these tests run against makes this expensive rather than merely wasteful: a film
 * shelf answers with megabytes, and it ignores the `limit` and `start` parameters outright, so there
 * is no way to ask it for a smaller slice. Re-fetching per visit was downloading the entire shelf
 * every time the viewer moved between categories.
 */
@RunWith(RobolectricTestRunner::class)
class VodCachingTest {

    private lateinit var server: MockWebServer
    private lateinit var database: VodDatabase
    private lateinit var repository: VodRepository
    private lateinit var profile: ProviderProfile

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            VodDatabase::class.java,
        ).allowMainThreadQueries().build()

        profile = ProviderProfile(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret",
        )
        repository = VodRepository(
            profile = profile,
            database = database,
            ioDispatcher = Dispatchers.IO,
            api = XtreamNetwork.createVodApi(profile),
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun `a shelf already fetched is not fetched again`() = runBlocking {
        enqueueFilms()
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)

        // The whole point. One download, however many times the viewer comes back to the shelf.
        // Recorded once and asserted once: the recorder drains the server's queue, so asking twice
        // would report zero the second time and look like a caching bug.
        val requests = recordedRequests()
        assertEquals(
            "requests seen: $requests",
            1,
            requests.count { it.contains("action=get_vod_streams") },
        )
    }

    @Test
    fun `a deliberate refresh fetches again`() = runBlocking {
        enqueueFilms()
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        enqueueFilms()

        repository.refreshCategory("10", ContentKind.MOVIE)

        // The escape hatch. With no way to ask the panel what changed, this is how a viewer picks up
        // new titles - so it has to actually re-fetch, not be short-circuited by the cache.
        assertEquals(2, recordedRequests().count { it.contains("action=get_vod_streams") })
    }

    @Test
    fun `a failed fetch does not mark the shelf as synced`() = runBlocking {
        // Three attempts, each consuming a response, all unparseable.
        repeat(3) { enqueue("""[{"stream_id":1,"name":"Broken""") }

        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        assertEquals(null, repository.categorySyncedAt("10", ContentKind.MOVIE))

        enqueueFilms()
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)

        // Marking it synced despite the failure would leave the shelf permanently empty and
        // permanently "already downloaded", which looks exactly like a provider with no films.
        assertTrue(repository.categorySyncedAt("10", ContentKind.MOVIE) != null)
        // Three retries for the failure, then one that worked.
        assertEquals(4, recordedRequests().count { it.contains("action=get_vod_streams") })
    }

    @Test
    fun `a film shelf and a series shelf of the same id are tracked separately`() = runBlocking {
        enqueueFilms()
        repository.ensureCategoryLoaded("7", ContentKind.MOVIE)
        enqueue("""[{"series_id":9,"name":"A Show"}]""")
        repository.ensureCategoryLoaded("7", ContentKind.SERIES)

        // The two namespaces share the id space, so keying on the id alone would record the film
        // shelf as having loaded the series one, leaving that shelf empty with nothing to explain it.
        assertTrue(repository.categorySyncedAt("7", ContentKind.MOVIE) != null)
        assertTrue(repository.categorySyncedAt("7", ContentKind.SERIES) != null)

        val requests = recordedRequests()
        assertEquals(1, requests.count { it.contains("action=get_vod_streams") })
        assertEquals(1, requests.count { it.contains("action=get_series") })
    }

    @Test
    fun `an empty shelf is recorded as synced rather than retried forever`() = runBlocking {
        enqueue("[]")
        repository.ensureCategoryLoaded("11", ContentKind.MOVIE)
        repository.ensureCategoryLoaded("11", ContentKind.MOVIE)

        // A genuinely empty category is a real answer, not a failure. Retrying it on every visit
        // would be a request the provider can never satisfy differently.
        assertEquals(1, recordedRequests().count { it.contains("action=get_vod_streams") })
    }

    private fun enqueueFilms() {
        enqueue(
            """
            [{"stream_id":1,"name":"A Film","container_extension":"mp4","category_id":"10"},
             {"stream_id":2,"name":"Another Film","container_extension":"mp4","category_id":"10"}]
            """.trimIndent(),
        )
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }

    private fun recordedRequests(): List<String> {
        val requests = mutableListOf<String>()
        // A short-but-not-tiny timeout. A single millisecond races the server's dispatcher and
        // reports zero requests for a call that demonstrably made one, which reads as a caching bug
        // rather than a flaky count.
        while (true) {
            val request = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            requests += request.path.orEmpty()
        }
        return requests
    }
}

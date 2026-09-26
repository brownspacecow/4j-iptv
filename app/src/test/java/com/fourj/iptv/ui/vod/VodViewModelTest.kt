package com.fourj.iptv.ui.vod

import androidx.room.Room
import com.fourj.iptv.data.local.SearchIndexDatabase
import com.fourj.iptv.data.local.VodDatabase
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.data.repository.SearchRepository
import com.fourj.iptv.data.repository.VodRepository
import com.fourj.iptv.domain.model.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.TimeUnit

/**
 * The browse screen's state machine.
 *
 * These use real time and a real socket rather than a virtual-time scheduler. The ViewModel's work
 * is a genuine HTTP call, and a test scheduler cannot wait for an I/O thread - it returns
 * immediately and every assertion then races the network. Polling the exposed state with a deadline
 * tests the same code and actually waits for it.
 *
 * The test that matters most is [a loaded category is not fetched over and over]. The bug it guards
 * against is not a wrong value but a wrong *rate*: loading a category writes to the database, the
 * database flow emits, the emission updates the state, and a collector keyed on the whole state
 * restarts the load - which writes again. Against a real provider that is a request loop fast
 * enough to burn the account's connection allowance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VodViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var database: VodDatabase
    private lateinit var searchDatabase: SearchIndexDatabase
    private lateinit var repository: VodRepository
    private lateinit var searchRepository: SearchRepository
    private lateinit var profile: ProviderProfile

    @Before
    fun setUp() {
        // Unconfined, so viewModelScope bodies start on the calling thread. Waiting is done by
        // polling the state, not by advancing a scheduler.
        Dispatchers.setMain(Dispatchers.Unconfined)

        server = MockWebServer()
        server.start()

        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            VodDatabase::class.java,
        ).allowMainThreadQueries().build()

        // The search index gets its own in-memory database, as it does in the app. It is a separate
        // file there because it holds nothing but derived names and is safe to discard, and the
        // tests keep that separation so a change to either schema cannot quietly alter the other.
        searchDatabase = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            SearchIndexDatabase::class.java,
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
        searchRepository = SearchRepository(
            profile = profile,
            database = searchDatabase,
            liveApi = XtreamNetwork.createApi(profile, debugLogging = false),
            vodApi = XtreamNetwork.createVodApi(profile),
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        database.close()
        searchDatabase.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }

    private fun enqueueCategories() {
        enqueue("""[{"category_id":"10","category_name":"Movies-New Releases"}]""")
        enqueue("""[{"category_id":"20","category_name":"Series-Drama"}]""")
    }

    private fun enqueueFilms(count: Int = 1) {
        repeat(count) {
            enqueue(
                """[{"num":1,"name":"A Film","stream_id":1,"category_id":"10",
                    "container_extension":"mp4"}]""",
            )
        }
    }

    /**
     * Wait until [condition] holds, or fail reporting what the state actually was.
     *
     * Generous on purpose. This is a real-time wait on a real socket, and the suite runs Robolectric
     * tests in one JVM alongside a Gradle daemon and an emulator, so a tight deadline turns into a
     * test that fails on a busy machine and passes on a quiet one - which is worse than no test.
     */
    private fun <T> StateFlow<T>.await(
        timeoutMillis: Long = 45_000,
        describe: (T) -> String,
        condition: (T) -> Boolean,
    ): T = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last = value
        while (System.currentTimeMillis() < deadline) {
            last = value
            if (condition(last)) return@runBlocking last
            delay(POLL_INTERVAL_MS)
        }
        throw AssertionError("timed out after ${timeoutMillis}ms; last state: ${describe(last)}")
    }

    private fun countRequests(action: String): Int {
        var count = 0
        while (true) {
            val request = server.takeRequest(50, TimeUnit.MILLISECONDS) ?: break
            if (request.path.orEmpty().contains("action=$action")) count++
        }
        return count
    }

    @Test
    fun `a loaded category is not fetched over and over`() {
        enqueueCategories()
        // Spare responses so a runaway loop has something to consume instead of stalling on an
        // empty queue, which would make the loop look like a hang rather than a loop.
        enqueueFilms(count = 40)

        val viewModel = VodViewModel(repository, searchRepository, profile)
        viewModel.state.await(
            describe = { "movies=${it.movies.map { m -> m.name }} categories=${it.categories.map { c -> c.id }}" },
            condition = { it.movies.isNotEmpty() },
        )

        // Give a loop time to show itself. A correct implementation makes exactly one request.
        Thread.sleep(SETTLE_MILLIS)
        val fetches = countRequests("get_vod_streams")
        assertTrue("expected at most 2 fetches of the film category, got $fetches", fetches <= 2)
    }

    @Test
    fun `categories load and the first is adopted so the screen is never empty`() {
        enqueueCategories()
        enqueueFilms()

        val viewModel = VodViewModel(repository, searchRepository, profile)
        val state = viewModel.state.await(
            describe = { "selected=${it.selectedCategoryId} categories=${it.categories.map { c -> c.id }}" },
            condition = { it.selectedCategoryId != null && it.movies.isNotEmpty() },
        )

        assertEquals(listOf("10"), state.categories.map { it.id })
        assertEquals("10", state.selectedCategoryId)
        assertEquals(listOf("A Film"), state.movies.map { it.name })
    }

    @Test
    fun `switching section shows that section's categories and contents`() {
        enqueueCategories()
        enqueueFilms()
        enqueue("""[{"num":1,"name":"A Show","series_id":3001,"category_id":"20"}]""")

        val viewModel = VodViewModel(repository, searchRepository, profile)
        viewModel.state.await(
            describe = { "movies=${it.movies.size}" },
            condition = { it.movies.isNotEmpty() },
        )

        viewModel.browse(VodSection.SERIES)
        val state = viewModel.state.await(
            describe = { "categories=${it.categories.map { c -> c.id }} series=${it.series.map { s -> s.name }}" },
            condition = { it.section == VodSection.SERIES && it.series.isNotEmpty() },
        )

        assertEquals(listOf("20"), state.categories.map { it.id })
        assertEquals(listOf("A Show"), state.series.map { it.name })
        // The other section's contents must not linger under the new tab.
        assertEquals(emptyList<String>(), state.movies.map { it.name })
    }

    @Test
    fun `an empty category reports empty rather than failing`() {
        enqueueCategories()
        enqueue("[]")

        val viewModel = VodViewModel(repository, searchRepository, profile)
        val state = viewModel.state.await(
            describe = { "selected=${it.selectedCategoryId} loading=${it.isLoadingItems}" },
            condition = { it.selectedCategoryId != null && !it.isLoadingItems },
        )

        assertEquals("10", state.selectedCategoryId)
        assertEquals(emptyList<String>(), state.movies.map { it.name })
    }

    @Test
    fun `a film with no container extension still produces a playable url`() {
        enqueueCategories()
        enqueue("""[{"num":1,"name":"No Ext","stream_id":5,"category_id":"10"}]""")

        val viewModel = VodViewModel(repository, searchRepository, profile)
        val state = viewModel.state.await(
            describe = { "movies=${it.movies.map { m -> m.name }}" },
            condition = { it.movies.isNotEmpty() },
        )

        val movie = state.movies.single()
        assertTrue(viewModel.movieUrl(movie).endsWith("/5.mp4"))
    }

    @Test
    fun `the query filters what is on screen without refetching`() {
        enqueueCategories()
        enqueueFilms()

        val viewModel = VodViewModel(repository, searchRepository, profile)
        viewModel.state.await(
            describe = { "movies=${it.movies.size}" },
            condition = { it.movies.isNotEmpty() },
        )

        viewModel.onQueryChange("nothing matches this")
        assertEquals(emptyList<String>(), viewModel.state.value.visibleMovies.map { it.name })

        viewModel.onQueryChange("a film")
        assertEquals(listOf("A Film"), viewModel.state.value.visibleMovies.map { it.name })
    }

    private companion object {
        const val POLL_INTERVAL_MS = 25L
        const val SETTLE_MILLIS = 1_200L
    }
}

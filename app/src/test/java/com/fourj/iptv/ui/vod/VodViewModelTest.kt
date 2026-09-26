package com.fourj.iptv.ui.vod

import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.PlaybackProgress
import com.fourj.iptv.testing.PanelFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private val panel = PanelFixture()
    private val repository = panel.vodRepository()

    // The search index gets its own in-memory database, as it does in the app. It is a separate file
    // there because it holds nothing but derived names and is safe to discard, and the tests keep
    // that separation so a change to either schema cannot quietly alter the other.
    private val searchRepository = panel.searchRepository()

    @Before
    fun setUp() {
        // Unconfined, so viewModelScope bodies start on the calling thread. Waiting is done by
        // polling the state, not by advancing a scheduler.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        panel.close()
    }

    private fun enqueue(body: String) = panel.enqueue(body)

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

    @Test
    fun `a loaded category is not fetched over and over`() {
        enqueueCategories()
        // Spare responses so a runaway loop has something to consume instead of stalling on an
        // empty queue, which would make the loop look like a hang rather than a loop.
        enqueueFilms(count = 40)

        val viewModel = VodViewModel(repository, searchRepository, panel.profile)
        viewModel.state.await(
            describe = { "movies=${it.movies.map { m -> m.name }} categories=${it.categories.map { c -> c.id }}" },
            condition = { it.movies.isNotEmpty() },
        )

        // Give a loop time to show itself. A correct implementation makes exactly one request.
        Thread.sleep(SETTLE_MILLIS)
        val fetches = panel.countRequests("get_vod_streams")
        assertTrue("expected at most 2 fetches of the film category, got $fetches", fetches <= 2)
    }

    @Test
    fun `categories load and the first is adopted so the screen is never empty`() {
        enqueueCategories()
        enqueueFilms()

        val viewModel = VodViewModel(repository, searchRepository, panel.profile)
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

        val viewModel = VodViewModel(repository, searchRepository, panel.profile)
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

        val viewModel = VodViewModel(repository, searchRepository, panel.profile)
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

        val viewModel = VodViewModel(repository, searchRepository, panel.profile)
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

        val viewModel = VodViewModel(repository, searchRepository, panel.profile)
        viewModel.state.await(
            describe = { "movies=${it.movies.size}" },
            condition = { it.movies.isNotEmpty() },
        )

        viewModel.onQueryChange("nothing matches this")
        assertEquals(emptyList<String>(), viewModel.state.value.visibleMovies.map { it.name })

        viewModel.onQueryChange("a film")
        assertEquals(listOf("A Film"), viewModel.state.value.visibleMovies.map { it.name })
    }

    /**
     * A film's resume position has to survive the round trip back into a playable film.
     *
     * The bug this guards: the player saved a position without the film's numeric id, so the stored
     * row carried id 0. "Continue watching" then looked the film up by that id, found nothing, and
     * the row silently did nothing when pressed - a whole advertised feature that rendered a card
     * with a progress bar and then ignored OK. Nothing crashed and nothing was logged, which is why
     * it survived.
     *
     * The id is the whole mechanism, so this asserts both halves: a row carrying the real id
     * resolves, and the same row stripped of it does not.
     */
    @Test
    fun `a saved film position resolves back to the film it was saved for`() = runBlocking {
        enqueueCategories()
        enqueue("""[{"num":1,"name":"A Film","stream_id":7,"category_id":"10","container_extension":"mp4"}]""")

        val viewModel = VodViewModel(repository, searchRepository, panel.profile)
        val state = viewModel.state.await(
            describe = { "movies=${it.movies.map { m -> m.name }}" },
            condition = { it.movies.isNotEmpty() },
        )
        val movie = state.movies.single()

        val saved = PlaybackProgress(
            contentKey = viewModel.progressKeyForMovie(movie.id),
            kind = ContentKind.MOVIE,
            title = movie.name,
            subtitle = null,
            positionSeconds = 600,
            durationSeconds = 3_600,
            posterUrl = null,
            updatedAtMillis = 1_700_000_000_000,
            contentId = movie.id,
        )
        viewModel.saveProgress(saved)
        // saveProgress hops to viewModelScope, so the write is not done when it returns.
        val stored = awaitLibraryRow(viewModel, saved.contentKey)

        val resolved = viewModel.resolveForResume(stored)
        assertTrue("a film in the cache must resolve, got $resolved", resolved is Resumable.FilmItem)
        assertEquals(movie.id, (resolved as Resumable.FilmItem).movie.id)
        assertEquals(600, resolved.resumeSeconds)
        assertTrue(resolved.url.endsWith("/7.mp4"))

        // And the failure mode itself: with the id missing, the lookup misses and returns null
        // rather than opening an empty player. This is what the row used to look like.
        assertNull(viewModel.resolveForResume(stored.copy(contentId = 0)))
    }

    /** Wait for a saved position to reach the library, so a test can resolve what it stored. */
    private suspend fun awaitLibraryRow(
        viewModel: VodViewModel,
        contentKey: String,
    ): PlaybackProgress {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val found = viewModel.library.value.continueWatching.firstOrNull { it.contentKey == contentKey }
            if (found != null) return found
            delay(POLL_INTERVAL_MS)
        }
        throw AssertionError("progress for $contentKey never reached the library")
    }

    private companion object {
        const val POLL_INTERVAL_MS = 25L
        const val SETTLE_MILLIS = 1_200L
    }
}

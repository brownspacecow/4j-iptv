package com.fourj.iptv.data.repository

import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.testing.PanelFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private val panel = PanelFixture()
    private val repository = panel.vodRepository()

    @After
    fun tearDown() = panel.close()

    @Test
    fun `a shelf already fetched is not fetched again`() = runBlocking {
        enqueueFilms()
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)

        // The whole point. One download, however many times the viewer comes back to the shelf.
        assertEquals(1, panel.countRequests("get_vod_streams"))
    }

    @Test
    fun `a deliberate refresh fetches again`() = runBlocking {
        enqueueFilms()
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        enqueueFilms()

        repository.refreshCategory("10", ContentKind.MOVIE)

        // The escape hatch. With no way to ask the panel what changed, this is how a viewer picks up
        // new titles - so it has to actually re-fetch, not be short-circuited by the cache.
        assertEquals(2, panel.countRequests("get_vod_streams"))
    }

    @Test
    fun `a failed fetch does not mark the shelf as synced`() = runBlocking {
        // Three attempts, each consuming a response, all unparseable.
        panel.enqueueTimes(3, """[{"stream_id":1,"name":"Broken"""")

        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        assertNull(repository.categorySyncedAt("10", ContentKind.MOVIE))

        enqueueFilms()
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)

        // Marking it synced despite the failure would leave the shelf permanently empty and
        // permanently "already downloaded", which looks exactly like a provider with no films.
        assertNotNull(repository.categorySyncedAt("10", ContentKind.MOVIE))
        // Three retries for the failure, then one that worked.
        assertEquals(4, panel.countRequests("get_vod_streams"))
    }

    @Test
    fun `a film shelf and a series shelf of the same id are tracked separately`() = runBlocking {
        enqueueFilms()
        repository.ensureCategoryLoaded("7", ContentKind.MOVIE)
        panel.enqueue("""[{"series_id":9,"name":"A Show"}]""")
        repository.ensureCategoryLoaded("7", ContentKind.SERIES)

        // The two namespaces share the id space, so keying on the id alone would record the film
        // shelf as having loaded the series one, leaving that shelf empty with nothing to explain it.
        assertNotNull(repository.categorySyncedAt("7", ContentKind.MOVIE))
        assertNotNull(repository.categorySyncedAt("7", ContentKind.SERIES))

        assertEquals(1, panel.countRequests("get_vod_streams"))
        assertEquals(1, panel.countRequests("get_series"))
    }

    @Test
    fun `an empty shelf is recorded as synced rather than retried forever`() = runBlocking {
        panel.enqueue("[]")
        repository.ensureCategoryLoaded("11", ContentKind.MOVIE)
        repository.ensureCategoryLoaded("11", ContentKind.MOVIE)

        // A genuinely empty category is a real answer, not a failure. Retrying it on every visit
        // would be a request the provider can never satisfy differently.
        assertEquals(1, panel.countRequests("get_vod_streams"))
    }

    private fun enqueueFilms() {
        panel.enqueue(
            """
            [{"stream_id":1,"name":"A Film","container_extension":"mp4","category_id":"10"},
             {"stream_id":2,"name":"Another Film","container_extension":"mp4","category_id":"10"}]
            """.trimIndent(),
        )
    }
}


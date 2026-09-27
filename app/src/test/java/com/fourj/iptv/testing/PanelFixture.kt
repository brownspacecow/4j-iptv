package com.fourj.iptv.testing

import com.fourj.iptv.data.local.SearchIndexDao
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.data.repository.SearchRepository
import com.fourj.iptv.data.repository.VodRepository
import com.fourj.iptv.domain.model.ProviderProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/**
 * A stand-in Xtream panel over a real socket, with the repositories pointed at it.
 *
 * Every test that talks to a provider goes through here rather than assembling its own server,
 * profile and Retrofit pair. That matters less for the lines saved than for the behavior: when the
 * retry helper changes from three attempts to two, or the profile needs another field, it changes
 * in one place instead of four, and the four copies cannot drift apart.
 *
 * The credentials are fixed. Tests assert on constructed stream URLs, and those embed the username
 * and password, so a per-test value would mean every such assertion had to be written in terms of
 * it for no benefit.
 *
 * The databases are created on first use and shared, so a test can hold the repository and still
 * reach the rows behind it. That is why they are not constructor arguments.
 *
 * Typical use:
 * ```
 * private val panel = PanelFixture()
 * private val repository = panel.vodRepository()
 *
 * @After fun tearDown() = panel.close()
 * ```
 */
class PanelFixture(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val server = MockWebServer().apply { start() }

    private val vodDatabase = lazy { inMemoryVodDatabase() }
    private val searchIndexDatabase = lazy { inMemorySearchIndexDatabase() }

    /** Memoised so repeated [recordedRequests] calls agree with each other. See its documentation. */
    private var recorded: List<String>? = null

    val profile = ProviderProfile(
        baseUrl = server.url("/").toString().trimEnd('/'),
        username = "alice",
        password = "secret",
    )

    fun vodRepository(ioDispatcher: CoroutineDispatcher = this.ioDispatcher): VodRepository =
        VodRepository(
            profile = profile,
            database = vodDatabase.value,
            ioDispatcher = ioDispatcher,
            api = XtreamNetwork.createVodApi(profile),
        )

    fun searchRepository(ioDispatcher: CoroutineDispatcher = this.ioDispatcher): SearchRepository =
        SearchRepository(
            profile = profile,
            database = searchIndexDatabase.value,
            liveApi = XtreamNetwork.createApi(profile, debugLogging = false),
            vodApi = XtreamNetwork.createVodApi(profile),
            ioDispatcher = ioDispatcher,
        )

    /**
     * The rows behind [searchRepository], for the few tests that assert on the index directly rather
     * than through a search.
     */
    fun searchIndexDao(): SearchIndexDao = searchIndexDatabase.value.searchIndexDao()

    fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }

    /**
     * Enqueue the same body [times] times.
     *
     * The retry helper makes three requests before giving up and each one consumes a response, so a
     * test that wants a shelf to fail has to enqueue the failure three times. Enqueueing it once
     * serves truncated JSON to the *next* shelf instead, which quietly turns a test about the code
     * into a measurement of the mock.
     */
    fun enqueueTimes(times: Int, body: String) = repeat(times) { enqueue(body) }

    /**
     * Every request the server has seen.
     *
     * Read once and remembered, so a test can assert against several actions without the second
     * assertion seeing an empty queue. That trap is worth naming: the underlying recorder *does*
     * drain the server, so calling it twice really would report zero the second time, and a test
     * that counted one action and then another would fail for a reason that has nothing to do with
     * the code under test.
     *
     * The drain itself needs a timeout that is short but not tiny. A single millisecond races the
     * server's own dispatcher and reports zero requests for a call that demonstrably made one,
     * which reads as a caching bug rather than as a flaky count.
     */
    fun recordedRequests(): List<String> = recorded ?: buildList {
        while (true) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: break
            add(request.path.orEmpty())
        }
    }.also { recorded = it }

    /** How many requests asked for [action], e.g. `"get_vod_streams"`. */
    fun countRequests(action: String): Int =
        recordedRequests().count { it.contains("action=$action") }

    override fun close() {
        // A database that was never opened needs no close, and forcing one just to close it would
        // build a schema for nothing.
        if (vodDatabase.isInitialized()) vodDatabase.value.close()
        if (searchIndexDatabase.isInitialized()) searchIndexDatabase.value.close()
        server.shutdown()
    }
}

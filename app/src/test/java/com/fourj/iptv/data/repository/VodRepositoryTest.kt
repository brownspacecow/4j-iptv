package com.fourj.iptv.data.repository

import androidx.room.Room
import com.fourj.iptv.data.local.VodDatabase
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.Favourite
import com.fourj.iptv.domain.model.PlaybackProgress
import com.fourj.iptv.domain.model.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The on-demand catalogue, exercised against a stand-in panel over a real socket.
 *
 * Room runs in memory and Retrofit talks to a [MockWebServer], so the whole chain - HTTP, JSON,
 * mapping, the database - is covered without a device. That matters because the failures worth
 * catching here are all in the seams: a category stored under the wrong kind, a series whose
 * episodes never land, a resume position that reads back wrong.
 */
@RunWith(RobolectricTestRunner::class)
class VodRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var database: VodDatabase
    private lateinit var repository: VodRepository

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val profile = ProviderProfile(
        baseUrl = "http://panel.test",
        username = "alice",
        password = "secret",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            VodDatabase::class.java,
        ).allowMainThreadQueries().build()

        val panelProfile = profile.copy(baseUrl = server.url("/").toString().trimEnd('/'))
        repository = VodRepository(
            profile = panelProfile,
            database = database,
            // Unconfined so a flow read resolves without a dispatcher being advanced by hand.
            ioDispatcher = Dispatchers.Unconfined,
            api = XtreamNetwork.createVodApi(panelProfile),
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }

    // -----------------------------------------------------------------------------------------
    // Categories
    // -----------------------------------------------------------------------------------------

    /**
     * The bug this guards: a real provider returns films and series in one combined category list,
     * distinguished only by names. If both are stored under a single kind then the Movies tab can
     * select a series category and display nothing at all.
     */
    @Test
    fun `stores film and series categories under their own kinds`() = runTest {
        enqueue("""[{"category_id":"10","category_name":"Movies-New Releases"}]""")
        val filmsResult = repository.refreshVodCategories()
        assertTrue("refresh failed: ${filmsResult.exceptionOrNull()}", filmsResult.isSuccess)
        assertEquals(1, filmsResult.getOrThrow().size)

        enqueue("""[{"category_id":"20","category_name":"Series-Drama"}]""")
        assertTrue(repository.refreshSeriesCategories().isSuccess)

        val films = repository.observeVodCategories(ContentKind.MOVIE).first()
        val shows = repository.observeVodCategories(ContentKind.SERIES).first()

        assertEquals(listOf("10"), films.map { it.id })
        assertEquals(listOf("20"), shows.map { it.id })
        assertEquals("Movies-New Releases", films.single().name)
    }

    @Test
    fun `a category that reuses an id across kinds does not overwrite the other`() = runTest {
        // Both namespaces use bare numeric ids, so a collision is entirely possible and the kind
        // has to be part of the primary key for both rows to survive.
        enqueue("""[{"category_id":"7","category_name":"Films"}]""")
        repository.refreshVodCategories()

        enqueue("""[{"category_id":"7","category_name":"Shows"}]""")
        repository.refreshSeriesCategories()

        assertEquals("Films", repository.observeVodCategories(ContentKind.MOVIE).first().single().name)
        assertEquals("Shows", repository.observeVodCategories(ContentKind.SERIES).first().single().name)
    }

    @Test
    fun `skips categories with no id or no name`() = runTest {
        enqueue(
            """[
                {"category_id":"10","category_name":"Keep"},
                {"category_id":"","category_name":"No id"},
                {"category_id":"11","category_name":"  "}
            ]""".trimIndent(),
        )
        repository.refreshVodCategories()

        assertEquals(listOf("10"), repository.observeVodCategories(ContentKind.MOVIE).first().map { it.id })
    }

    // -----------------------------------------------------------------------------------------
    // Films
    // -----------------------------------------------------------------------------------------

    @Test
    fun `loads a film category and reads it back`() = runTest {
        enqueue(VOD_STREAMS)
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)

        val movies = repository.observeMovies("10").first()
        assertEquals(3, movies.size)
        assertEquals("Mock Film: The Longest Night", movies.first().name)
        assertEquals(1001, movies.first().id)
        assertEquals(8.4, movies.first().rating!!, 0.001)
    }

    @Test
    fun `replaces a film category rather than accumulating duplicates`() = runTest {
        enqueue(VOD_STREAMS)
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        enqueue("""[{"num":1,"name":"Only Film Now","stream_id":9999,"category_id":"10"}]""")
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)

        val movies = repository.observeMovies("10").first()
        assertEquals(listOf(9999), movies.map { it.id })
    }

    @Test
    fun `ignores films with no id`() = runTest {
        enqueue(
            """[
                {"num":1,"name":"Real","stream_id":1,"category_id":"10"},
                {"num":2,"name":"No id","stream_id":0,"category_id":"10"},
                {"num":3,"name":"  ","stream_id":2,"category_id":"10"}
            ]""".trimIndent(),
        )
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)

        assertEquals(listOf(1), repository.observeMovies("10").first().map { it.id })
    }

    @Test
    fun `prefers the provider's direct source over a constructed url`() = runTest {
        enqueue(
            """[{"num":1,"name":"Direct","stream_id":5,"category_id":"10",
                "direct_source":"https://cdn.test/5.mkv"}]""".trimIndent(),
        )
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        val movie = repository.observeMovies("10").first().single()

        assertEquals("https://cdn.test/5.mkv", repository.movieStreamUrl(movie))
    }

    @Test
    fun `constructs a movie url when the provider supplies none`() = runTest {
        enqueue(
            """[{"num":1,"name":"Constructed","stream_id":7,"category_id":"10",
                "container_extension":"mkv"}]""".trimIndent(),
        )
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        val movie = repository.observeMovies("10").first().single()

        // The panel under test is the MockWebServer, so the constructed url carries its authority.
        assertEquals(
            "${server.url("/").toString().trimEnd('/')}/movie/alice/secret/7.mkv",
            repository.movieStreamUrl(movie),
        )
    }

    @Test
    fun `falls back to mp4 when the container extension is missing`() = runTest {
        enqueue("""[{"num":1,"name":"No ext","stream_id":8,"category_id":"10"}]""")
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        val movie = repository.observeMovies("10").first().single()

        assertTrue(repository.movieStreamUrl(movie).endsWith("/8.mp4"))
    }

    @Test
    fun `a non-http direct source is ignored rather than played`() = runTest {
        // Panels have been seen carrying values like "0" or a bare filename in this field, which
        // would otherwise be handed to the player as a URL.
        enqueue(
            """[{"num":1,"name":"Bad","stream_id":9,"category_id":"10",
                "direct_source":"not-a-url","container_extension":"mp4"}]""".trimIndent(),
        )
        repository.ensureCategoryLoaded("10", ContentKind.MOVIE)
        val movie = repository.observeMovies("10").first().single()

        assertTrue(repository.movieStreamUrl(movie).contains("/movie/alice/secret/9.mp4"))
    }

    // -----------------------------------------------------------------------------------------
    // Series
    // -----------------------------------------------------------------------------------------

    @Test
    fun `loads a series category`() = runTest {
        enqueue(SERIES)
        repository.ensureCategoryLoaded("20", ContentKind.SERIES)

        val shows = repository.observeSeries("20").first()
        assertEquals(1, shows.size)
        assertEquals(3001, shows.single().id)
        assertEquals("Mock Drama Series", shows.single().name)
    }

    @Test
    fun `splits a series into seasons and episodes`() = runTest {
        enqueue(SERIES_INFO)
        val seasons = repository.loadSeriesDetail(3001).getOrThrow()

        assertEquals(listOf(1, 2), seasons.map { it.number })
        val s1 = repository.cachedEpisodes(3001, 1)
        assertEquals(3, s1.size)
        assertEquals(listOf(1, 2, 3), s1.map { it.episodeNumber })
    }

    @Test
    fun `an episode carries the url needed to play it`() = runTest {
        enqueue(SERIES_INFO)
        repository.loadSeriesDetail(3001)

        val episode = repository.cachedEpisodes(3001, 1).first()
        assertEquals("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", repository.episodeStreamUrl(episode))
        assertEquals(1, episode.seasonNumber)
        assertEquals(3001, episode.seriesId)
    }

    @Test
    fun `reloading a series replaces its episodes instead of duplicating them`() = runTest {
        enqueue(SERIES_INFO)
        repository.loadSeriesDetail(3001)
        enqueue(SERIES_INFO)
        repository.loadSeriesDetail(3001)

        assertEquals(3, repository.cachedEpisodes(3001, 1).size)
        assertEquals(3, repository.cachedEpisodes(3001, 2).size)
    }

    @Test
    fun `an empty series response is not an error`() = runTest {
        enqueue("""{"info":{"name":"Empty"},"episodes":{"season":[]}}""")
        val result = repository.loadSeriesDetail(123)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Int>(), result.getOrThrow().map { it.number })
    }

    /**
     * A real provider nests seasons at the top level, not under `episodes.season`.
     *
     * Reading only the documented shape made every series on that panel report zero seasons, and
     * a count of zero is indistinguishable from genuinely empty data - so this failed quietly in
     * the field rather than loudly. Both layouts have to be accepted.
     */
    @Test
    fun `reads a top-level seasons payload as well as the nested one`() = runTest {
        enqueue(
            """
            {
              "info": {"name": "Top Level Panel", "plot": "Seasons at the top."},
              "seasons": [
                {"air_date": "2024-01-01", "id": "1", "name": "Season 1", "episodes": [
                  {"id":"e1","episode_num":1,"title":"One","season":1,"info_hash":"h1",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://cdn.test/1.mp4","mime_type":"video/mp4"}}},
                  {"id":"e2","episode_num":2,"title":"Two","season":1,"info_hash":"h2",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://cdn.test/2.mp4","mime_type":"video/mp4"}}}
                ]},
                {"air_date": "2024-06-01", "id": "2", "name": "Season 2", "episodes": [
                  {"id":"e3","episode_num":1,"title":"One","season":2,"info_hash":"h3",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://cdn.test/3.mp4","mime_type":"video/mp4"}}}
                ]}
              ]
            }
            """.trimIndent(),
        )

        val seasons = repository.loadSeriesDetail(999).getOrThrow()
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(2, repository.cachedEpisodes(999, 1).size)
        assertEquals(1, repository.cachedEpisodes(999, 2).size)
    }

    /**
     * A real provider keys episodes by season number, and sends `episode_num` as a *string*.
     *
     * Reading only the documented `episodes.season` shape made every series on that panel report
     * zero episodes, and a count of zero is indistinguishable from genuinely empty data - so this
     * failed quietly in the field rather than loudly. The string-typed number matters too: decoding
     * `"1"` into an Int throws, and one such episode would take the whole series with it.
     */
    @Test
    fun `reads episodes keyed by season number with string-typed numbers`() = runTest {
        enqueue(
            """
            {
              "info": {"name": "Keyed Panel", "plot": "Episodes keyed by season."},
              "seasons": [
                {"air_date":"1957-04-24","episode_count":2,"id":19715,"name":"1957",
                 "season_number":1,"cover":"https://image.tmdb.org/x"}
              ],
              "episodes": {
                "1": [
                  {"id":"3054675","episode_num":"1","title":"S01E01","container_extension":"mkv",
                   "season":1,"direct_source":""},
                  {"id":"3054676","episode_num":"2","title":"S01E02","container_extension":"mkv",
                   "season":1,"direct_source":""}
                ],
                "2": [
                  {"id":"3054677","episode_num":"1","title":"S02E01","container_extension":"mkv",
                   "season":2,"direct_source":""}
                ]
              }
            }
            """.trimIndent(),
        )

        val seasons = repository.loadSeriesDetail(777).getOrThrow()
        assertEquals(listOf(1, 2), seasons.map { it.number })

        val s1 = repository.cachedEpisodes(777, 1)
        assertEquals(2, s1.size)
        assertEquals(listOf(1, 2), s1.map { it.episodeNumber })
        assertEquals("S01E01", s1.first().title)

        // The season number comes from the map key, so it must survive even when an episode omits it.
        val noSeasonField = repository.cachedEpisodes(777, 2)
        assertEquals(1, noSeasonField.size)
        assertEquals(2, noSeasonField.single().seasonNumber)
    }

    @Test
    fun `a flat direct source is used when there is no streams object`() = runTest {
        enqueue(
            """
            {"info":{"name":"x"},"episodes":{"1":[
              {"id":"a","episode_num":"1","title":"One","season":1,
               "direct_source":"https://cdn.test/1.mkv"}]}}
            """.trimIndent(),
        )
        repository.loadSeriesDetail(555)

        assertEquals("https://cdn.test/1.mkv", repository.episodeStreamUrl(repository.cachedEpisodes(555, 1).single()))
    }

    @Test
    fun `an episode with an empty direct source has no stream`() = runTest {
        // What the real panel actually sends: the metadata is all there and the stream is blank.
        // Reporting it as playable would hand the player an empty url.
        enqueue(
            """{"info":{"name":"x"},"episodes":{"1":[
              {"id":"a","episode_num":"1","title":"One","season":1,"direct_source":""}]}}""",
        )
        repository.loadSeriesDetail(556)

        assertEquals(null, repository.episodeStreamUrl(repository.cachedEpisodes(556, 1).single()))
    }

    @Test
    fun `the nested payload still works after the keyed one was added`() = runTest {
        enqueue(SERIES_INFO)
        val seasons = repository.loadSeriesDetail(3001).getOrThrow()

        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(3, repository.cachedEpisodes(3001, 1).size)
    }

    @Test
    fun `each payload shape reports which one it was`() = runTest {
        fun shapeOf(body: String) =
            json.decodeFromString<com.fourj.iptv.data.remote.xtream.SeriesInfoResponse>(body).shape()

        assertEquals("nested", shapeOf(SERIES_INFO))
        assertEquals("keyed-by-season", shapeOf("""{"episodes":{"1":[{"id":"a","info_hash":"h"}]}}"""))
        assertEquals("summaries-only", shapeOf("""{"seasons":[{"id":"1","season_number":1}]}"""))
        assertEquals("unrecognised", shapeOf("""{"info":{"name":"x"}}"""))
    }

    // -----------------------------------------------------------------------------------------
    // Continue watching
    // -----------------------------------------------------------------------------------------

    @Test
    fun `saves and reads back a resume position`() = runTest {
        val progress = PlaybackProgress(
            contentKey = contentKey(ContentKind.MOVIE, 1001),
            kind = ContentKind.MOVIE,
            title = "Mock Film: The Longest Night",
            subtitle = null,
            positionSeconds = 600,
            durationSeconds = 2400,
            posterUrl = null,
            updatedAtMillis = 1_700_000_000_000,
            contentId = 1001,
        )
        repository.saveProgress(progress)

        val read = repository.progressFor(ContentKind.MOVIE, 1001)
        assertNotNull(read)
        assertEquals(600, read!!.positionSeconds)
        assertEquals(0.25f, read.fraction, 0.001f)
        assertTrue(read.isResumable)
    }

    @Test
    fun `an episode resumes under its row key, not a hash of it`() = runTest {
        // An episode has no numeric id, so the key has to carry the panel's own identifier. Hashing
        // it to fit an Int would make the row unfindable and could collide with another episode.
        val rowKey = "3001:hash-3001-1-1"
        repository.saveProgress(
            PlaybackProgress(
                contentKey = episodeContentKey(rowKey),
                kind = ContentKind.EPISODE,
                title = "Mock Episode 1",
                subtitle = "Mock Drama Series - S1E1",
                positionSeconds = 120,
                durationSeconds = 2400,
                posterUrl = null,
                updatedAtMillis = 1_700_000_000_000,
            ),
        )

        val read = repository.progressForKey(episodeContentKey(rowKey))
        assertNotNull(read)
        assertEquals(rowKey, read!!.episodeRowKey)
        assertEquals(120L, read.positionSeconds)
    }

    @Test
    fun `an episode row can be found again by its key`() = runTest {
        enqueue(SERIES_INFO)
        repository.loadSeriesDetail(3001)

        val episode = repository.cachedEpisodes(3001, 1).first()
        val found = repository.findEpisode(episode.id)
        assertNotNull(found)
        assertEquals(episode.id, found!!.id)
        assertEquals(1, found.episodeNumber)
    }

    @Test
    fun `an unknown episode key resolves to nothing rather than throwing`() = runTest {
        assertEquals(null, repository.findEpisode("no-such-episode"))
    }

    @Test
    fun `progress is kept apart by kind so ids cannot collide`() = runTest {
        // Live channels, films and episodes all draw ids from the same panel and overlap freely.
        repository.saveProgress(progressFor(ContentKind.MOVIE, 42, "A film"))
        repository.saveProgress(progressFor(ContentKind.LIVE_CHANNEL, 42, "A channel"))

        assertEquals("A film", repository.progressFor(ContentKind.MOVIE, 42)!!.title)
        assertEquals("A channel", repository.progressFor(ContentKind.LIVE_CHANNEL, 42)!!.title)
    }

    @Test
    fun `a film barely started is not offered as resumable`() = runTest {
        val barely = progressFor(ContentKind.MOVIE, 1, "Barely").copy(
            positionSeconds = 1,
            durationSeconds = 10_000,
        )
        assertFalse(barely.isResumable)
    }

    @Test
    fun `a finished film is not offered as resumable`() = runTest {
        val finished = progressFor(ContentKind.MOVIE, 2, "Done").copy(
            positionSeconds = 9_900,
            durationSeconds = 10_000,
        )
        assertFalse(finished.isResumable)
    }

    @Test
    fun `an unknown duration cannot be resumable`() = runTest {
        val unknown = progressFor(ContentKind.MOVIE, 3, "Unknown").copy(durationSeconds = 0)
        assertFalse(unknown.isResumable)
        assertEquals(0f, unknown.fraction, 0.001f)
    }

    @Test
    fun `a non-episode progress row has no episode key`() = runTest {
        assertEquals(null, progressFor(ContentKind.MOVIE, 9, "A film").episodeRowKey)
    }

    @Test
    fun `clearing progress removes it`() = runTest {
        repository.saveProgress(progressFor(ContentKind.MOVIE, 5, "Temp"))
        assertNotNull(repository.progressFor(ContentKind.MOVIE, 5))

        repository.clearProgress(ContentKind.MOVIE, 5)
        assertNull(repository.progressFor(ContentKind.MOVIE, 5))
    }

    // -----------------------------------------------------------------------------------------
    // Favourites
    // -----------------------------------------------------------------------------------------

    @Test
    fun `toggling a favourite adds then removes it`() = runTest {
        val favourite = Favourite(
            contentKey = contentKey(ContentKind.MOVIE, 1001),
            kind = ContentKind.MOVIE,
            contentId = 1001,
            name = "Mock Film: The Longest Night",
            subtitle = null,
            posterUrl = null,
            addedAtMillis = 1_700_000_000_000,
        )

        assertFalse(repository.isFavourite(ContentKind.MOVIE, 1001))
        repository.toggleFavourite(favourite)
        assertTrue(repository.isFavourite(ContentKind.MOVIE, 1001))
        repository.toggleFavourite(favourite)
        assertFalse(repository.isFavourite(ContentKind.MOVIE, 1001))
    }

    @Test
    fun `favourites list newest first`() = runTest {
        repository.toggleFavourite(favourite(ContentKind.MOVIE, 1, "Older", 1_000))
        repository.toggleFavourite(favourite(ContentKind.MOVIE, 2, "Newer", 2_000))

        assertEquals(listOf("Newer", "Older"), repository.observeFavourites().first().map { it.name })
    }

    @Test
    fun `two episodes get distinct resume keys`() = runTest {
        // Regression. The lazy lists that show continue-watching are keyed on this string, and an
        // episode has no numeric id to key on - it used to fall back to zero, so the second episode
        // anyone watched produced a duplicate key and crashed the app. Found on the emulator.
        enqueue(SERIES_INFO)
        repository.loadSeriesDetail(3001)

        val keys = repository.cachedEpisodes(3001, 1).map { episodeContentKey(it.id) }
        assertEquals(3, keys.size)
        assertEquals(keys.size, keys.toSet().size)

        // And across seasons, since those share a list in the UI once combined.
        val acrossSeasons = keys + repository.cachedEpisodes(3001, 2).map { episodeContentKey(it.id) }
        assertEquals(6, acrossSeasons.toSet().size)
    }

    @Test
    fun `an episode favourite is found by its key and removes cleanly`() = runTest {
        // Episodes have no numeric id, so add, remove and lookup all go through the content key. A
        // remove that missed would leave the row stuck on forever, since a toggle would then only
        // ever add.
        val key = episodeContentKey("3001:hash-1")
        repository.addFavourite(
            Favourite(
                contentKey = key,
                kind = ContentKind.EPISODE,
                contentId = 0,
                name = "An Episode",
                subtitle = "A Show - S1E1",
                posterUrl = null,
                addedAtMillis = 1_000,
            ),
        )
        assertNotNull(repository.favouriteFor(key))
        assertEquals(ContentKind.EPISODE, repository.favouriteFor(key)!!.kind)

        repository.removeFavourite(key)
        assertEquals(null, repository.favouriteFor(key))
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private fun progressFor(kind: ContentKind, id: Int, title: String) = PlaybackProgress(
        contentKey = contentKey(kind, id),
        kind = kind,
        title = title,
        subtitle = null,
        positionSeconds = 60,
        durationSeconds = 3600,
        posterUrl = null,
        updatedAtMillis = 1_700_000_000_000,
        contentId = id,
    )

    private fun favourite(kind: ContentKind, id: Int, name: String, at: Long) = Favourite(
        contentKey = contentKey(kind, id),
        kind = kind,
        contentId = id,
        name = name,
        subtitle = null,
        posterUrl = null,
        addedAtMillis = at,
    )

    private companion object {
        const val VOD_STREAMS = """
            [
              {"num":1,"name":"Mock Film: The Longest Night","stream_id":1001,"category_id":"10",
               "rating":"8.4","rating_5based":8.4,"container_extension":"mp4"},
              {"num":2,"name":"Mock Film: Second Feature","stream_id":1002,"category_id":"10",
               "rating_5based":7.1,"container_extension":"mp4"},
              {"num":3,"name":"Mock Film: Third Feature","stream_id":1003,"category_id":"10",
               "rating_5based":9.0,"container_extension":"mp4"}
            ]
        """

        const val SERIES = """
            [
              {"num":1,"name":"Mock Drama Series","series_id":3001,"category_id":"20",
               "plot":"A mock series.","cast":"Someone","director":"A Director","genre":"Drama",
               "releaseDate":"2024-01-01","rating_5based":8.0,"cover":"https://img.test/1.jpg"}
            ]
        """

        const val SERIES_INFO = """
            {
              "info":{"name":"Mock Drama Series","plot":"A mock series."},
              "episodes":{"season":[
                {"id":"3001-s1","name":"Season 1","episodes":[
                  {"id":"e1","episode_num":1,"title":"One","season":1,"info_hash":"h1",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                                         "mime_type":"video/mp4"}}},
                  {"id":"e2","episode_num":2,"title":"Two","season":1,"info_hash":"h2",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                                         "mime_type":"video/mp4"}}},
                  {"id":"e3","episode_num":3,"title":"Three","season":1,"info_hash":"h3",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                                         "mime_type":"video/mp4"}}}
                ]},
                {"id":"3001-s2","name":"Season 2","episodes":[
                  {"id":"e4","episode_num":1,"title":"One","season":2,"info_hash":"h4",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                                         "mime_type":"video/mp4"}}},
                  {"id":"e5","episode_num":2,"title":"Two","season":2,"info_hash":"h5",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                                         "mime_type":"video/mp4"}}},
                  {"id":"e6","episode_num":3,"title":"Three","season":2,"info_hash":"h6",
                   "container_extension":"mp4",
                   "streams":{"direct":{"source":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                                         "mime_type":"video/mp4"}}}
                ]}
              ]}
            }
        """
    }
}

package com.fourj.iptv.data.repository

import android.util.Log
import com.fourj.iptv.data.local.IndexProgressEntity
import com.fourj.iptv.data.local.SearchIndexDatabase
import com.fourj.iptv.data.local.SearchIndexEntity
import com.fourj.iptv.data.remote.retrying
import com.fourj.iptv.data.remote.xtream.SeriesDto
import com.fourj.iptv.data.remote.xtream.VodApi
import com.fourj.iptv.data.remote.xtream.VodStreamDto
import com.fourj.iptv.data.remote.xtream.XtreamApi
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.LiveCategory
import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.domain.model.Movie
import com.fourj.iptv.domain.model.ProviderProfile
import com.fourj.iptv.domain.model.Series
import com.fourj.iptv.domain.model.VodCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** One hit, resolved far enough to be acted on. */
data class SearchHit(
    val kind: ContentKind,
    val contentId: Int,
    val name: String,
    val subtitle: String?,
    val categoryId: String?,
    val posterUrl: String?,
    val iconUrl: String?,
)

/** What one pass of the indexer achieved, including what it could not. */
data class IndexRun(
    val added: Int,
    /**
     * Shelves that could not be read, as `KIND:categoryId`.
     *
     * Reported rather than swallowed. A run that quietly dropped a shelf would leave search
     * permanently missing those titles while the coverage figure claimed everything was indexed,
     * and the viewer would have no way to tell that from "your provider does not carry it".
     */
    val skippedScopes: List<String>,
)

/** Raw index counters, as the database knows them. */
data class IndexCounts(
    val liveIndexed: Int,
    val movieIndexed: Int,
    val seriesIndexed: Int,
    val scopesComplete: Int,
) {
    val total: Int get() = liveIndexed + movieIndexed + seriesIndexed
}

/**
 * How much of the provider is searchable.
 *
 * [scopesTotal] is the number of categories the provider offers, which only the caller knows - it is
 * the same category list the browse screens already hold.
 *
 * [isComplete] is the number that decides whether a search can be trusted to be complete, and it is
 * tracked per category rather than inferred from a total. An index of 40,000 titles means nothing
 * on its own: it could be 40,000 titles fully indexed, or the first 40,000 of 200,000.
 */
data class IndexCoverage(
    val liveIndexed: Int,
    val movieIndexed: Int,
    val seriesIndexed: Int,
    val scopesTotal: Int,
    val scopesComplete: Int,
) {
    val total: Int get() = liveIndexed + movieIndexed + seriesIndexed
    val isComplete: Boolean get() = scopesTotal > 0 && scopesComplete >= scopesTotal
}

/**
 * Search over the provider's catalogue.
 *
 * **Why this is a local index and not a panel query.** Every server-side search action was tried
 * against the provider during testing: `search_streams`, `search`, `search_vod`, `search_movies`,
 * `search_movie` and `search_series` are all ignored, each answered with a login object. Passing
 * `search` to `get_vod_streams` or `get_series` is worse than useless - the parameter is ignored and
 * the whole catalogue comes back, large enough on this provider to have the app killed for memory
 * while trying to buffer it. So there is no server-side search to use, and the honest answer is an
 * index built on the device.
 *
 * **Why that is still the fast option.** A search here is a local SQLite query: no network, no
 * latency, and it works with the television offline. The cost is coverage, which is why coverage is
 * reported rather than assumed - a search box that silently finds nothing on a fresh install is
 * worse than no search box at all.
 */
class SearchRepository(
    private val profile: ProviderProfile,
    private val database: SearchIndexDatabase,
    private val liveApi: XtreamApi,
    private val vodApi: VodApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val dao get() = database.searchIndexDao()

    /**
     * Page size for the indexer.
     *
     * Chosen against this provider's habit of truncating large responses - a 2.2 MB body was cut off
     * mid-JSON during testing, repeatedly. Five hundred titles lands comfortably under that, and a
     * page shorter than requested is the signal that a category is finished.
     */
    private val pageSize = 500

    /**
     * Run a search.
     *
     * Suspending rather than synchronous on purpose: this runs on every keystroke, and a blocking
     * disk read on the main dispatcher is a dropped frame per character typed.
     */
    suspend fun search(term: String, limit: Int = DEFAULT_LIMIT): List<SearchHit> =
        withContext(ioDispatcher) {
            val needle = normaliseSearchTerm(term)
            if (needle.isEmpty()) return@withContext emptyList()
            dao.search(needle, limit).map { it.toHit() }
        }

    /**
     * Add live channels the app has already fetched.
     *
     * Free coverage: the browse screens have these in hand, so indexing them costs nothing but a
     * write. This is what makes search useful without anyone waiting for a background job.
     */
    suspend fun indexChannels(channels: List<LiveChannel>, categoryName: String?) =
        withContext(ioDispatcher) {
            if (channels.isEmpty()) return@withContext
            val rows = channels.mapNotNull { channel ->
                val name = channel.name.trim()
                if (name.isEmpty() || channel.streamId == 0) return@mapNotNull null
                SearchIndexEntity(
                    key = indexKey(ContentKind.LIVE_CHANNEL, channel.streamId),
                    kind = ContentKind.LIVE_CHANNEL.name,
                    contentId = channel.streamId,
                    name = name,
                    nameLower = name.lowercase(),
                    subtitle = categoryName,
                    categoryId = channel.categoryId,
                    posterUrl = null,
                    iconUrl = channel.iconUrl,
                )
            }
            if (rows.isNotEmpty()) dao.upsertAll(rows)
        }

    /** As [indexChannels], for films. */
    suspend fun indexMovies(movies: List<Movie>, categoryName: String?) =
        withContext(ioDispatcher) {
            if (movies.isEmpty()) return@withContext
            val rows = movies.filter { it.name.isNotBlank() }.map { movie ->
                SearchIndexEntity(
                    key = indexKey(ContentKind.MOVIE, movie.id),
                    kind = ContentKind.MOVIE.name,
                    contentId = movie.id,
                    name = movie.name,
                    nameLower = movie.name.lowercase(),
                    subtitle = categoryName,
                    categoryId = movie.categoryId,
                    posterUrl = movie.posterUrl,
                    iconUrl = null,
                )
            }
            if (rows.isNotEmpty()) dao.upsertAll(rows)
        }

    /** As [indexChannels], for series. */
    suspend fun indexSeries(series: List<Series>, categoryName: String?) =
        withContext(ioDispatcher) {
            if (series.isEmpty()) return@withContext
            val rows = series.filter { it.name.isNotBlank() }.map { item ->
                SearchIndexEntity(
                    key = indexKey(ContentKind.SERIES, item.id),
                    kind = ContentKind.SERIES.name,
                    contentId = item.id,
                    name = item.name,
                    nameLower = item.name.lowercase(),
                    subtitle = categoryName,
                    categoryId = item.categoryId,
                    posterUrl = item.posterUrl,
                    iconUrl = null,
                )
            }
            if (rows.isNotEmpty()) dao.upsertAll(rows)
        }

    /**
     * Indexed counts and how many categories are finished.
     *
     * Deliberately not reporting a "total" here: the repository does not know how many categories
     * the provider offers, and a total that is really "however many we happened to fetch" is how a
     * partial index gets mistaken for a complete one. The caller pairs this with the category list
     * it already holds and decides what completeness means.
     */
    fun observeCounts(): Flow<IndexCounts> =
        combine(
            dao.observeIndexedCount(ContentKind.LIVE_CHANNEL.name),
            dao.observeIndexedCount(ContentKind.MOVIE.name),
            dao.observeIndexedCount(ContentKind.SERIES.name),
            dao.observeAllProgress(),
        ) { live, movies, series, progress ->
            IndexCounts(
                liveIndexed = live,
                movieIndexed = movies,
                seriesIndexed = series,
                scopesComplete = progress.count { it.complete },
            )
        }

    suspend fun currentCounts(): IndexCounts = withContext(ioDispatcher) {
        IndexCounts(
            liveIndexed = dao.indexedCount(ContentKind.LIVE_CHANNEL.name),
            movieIndexed = dao.indexedCount(ContentKind.MOVIE.name),
            seriesIndexed = dao.indexedCount(ContentKind.SERIES.name),
            scopesComplete = dao.allProgress().count { it.complete },
        )
    }

    /**
     * Walk every category the provider offers, a page at a time, filling the index.
     *
     * Resumable: each category records how far it got, so an app restart continues rather than
     * re-downloading. Sequential on purpose - a hundred parallel requests at one panel is how a
     * provider starts refusing them, and the viewer is trying to watch something at the same time.
     *
     * **A shelf that cannot be read is skipped, not fatal.** This provider truncates large responses
     * and several live categories are too big to come back whole, so an uncaught failure here ends
     * the run at whichever shelf happened to be oversized. Testing hit exactly that after 2,084
     * channels, and with a few hundred shelves it is guaranteed to happen early - so indexing could
     * then never finish at all. A failed shelf is left unrecorded rather than marked complete, so a
     * later run retries it, and [IndexRun.skippedScopes] reports the gap rather than hiding it
     * behind a total that looks finished.
     */
    suspend fun indexEverything(
        liveCategories: List<LiveCategory>,
        vodCategories: List<VodCategory>,
        onProgress: suspend (added: Int) -> Unit = {},
    ): IndexRun = withContext(ioDispatcher) {
        var added = 0
        val skipped = mutableListOf<String>()

        fun noteFailure(scope: String, throwable: Throwable) {
            Log.w(TAG, "index $scope failed, skipping it", throwable)
            skipped += scope
        }

        for (category in liveCategories) {
            val scope = indexScope(ContentKind.LIVE_CHANNEL, category.id)
            added += runCatching { indexLiveCategory(category) }
                .onFailure { noteFailure(scope, it) }
                .getOrDefault(0)
            onProgress(added)
        }

        for (category in vodCategories) {
            val isSeries = category.id.startsWith(SERIES_CATEGORY_PREFIX)
            val kind = if (isSeries) ContentKind.SERIES else ContentKind.MOVIE
            val scope = indexScope(kind, category.id)
            added += runCatching {
                if (isSeries) indexSeriesCategory(category) else indexMovieCategory(category)
            }.onFailure { noteFailure(scope, it) }.getOrDefault(0)
            onProgress(added)
        }

        Log.i(TAG, "search index finished: $added added, ${skipped.size} shelf(s) skipped")
        IndexRun(added = added, skippedScopes = skipped)
    }

    /**
     * One request per live category.
     *
     * The panel offers no paging for live streams and a category fits in one response comfortably,
     * so this is marked done in a single step rather than paged.
     */
    private suspend fun indexLiveCategory(category: LiveCategory): Int {
        val scope = indexScope(ContentKind.LIVE_CHANNEL, category.id)
        if (dao.progressFor(scope)?.complete == true) return 0

        val channels = retrying(label = "index live[${category.id}]") {
            liveApi.liveStreams(categoryId = category.id)
        }.getOrThrow()

        val rows = channels.mapNotNull { dto ->
            val channel = dto.toEntity(sortOrder = 0)?.toModel() ?: return@mapNotNull null
            val name = channel.name.trim()
            if (name.isEmpty() || channel.streamId == 0) return@mapNotNull null
            SearchIndexEntity(
                key = indexKey(ContentKind.LIVE_CHANNEL, channel.streamId),
                kind = ContentKind.LIVE_CHANNEL.name,
                contentId = channel.streamId,
                name = name,
                nameLower = name.lowercase(),
                subtitle = category.name,
                categoryId = category.id,
                posterUrl = null,
                iconUrl = channel.iconUrl,
            )
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)

        dao.saveProgress(
            IndexProgressEntity(
                scope = scope,
                nextOffset = rows.size,
                indexed = rows.size,
                complete = true,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        return rows.size
    }

    /**
     * Walk one on-demand shelf a page at a time, returning how many titles were added.
     *
     * [fetch] asks the panel for the page at a given offset and returns it as a [Page]. It is
     * supplied by the caller because `get_series` and `get_vod_streams` return different DTO types;
     * the two used to be near-identical loops differing only in that call and the row mapping.
     *
     * **Stops when paging turns out not to work** - see [pagingIsHonoured].
     */
    private suspend fun indexShelf(
        category: VodCategory,
        kind: ContentKind,
        fetch: suspend (offset: Int) -> Page,
    ): Int {
        val scope = indexScope(kind, category.id)
        val resume = dao.progressFor(scope)
        if (resume?.complete == true) return 0

        var offset = resume?.nextOffset ?: 0
        var fetched = resume?.indexed ?: 0
        var added = 0
        var firstIdOfFirstPage: Int? = null

        while (true) {
            val page = fetch(offset)
            val honoursPaging = pagingIsHonoured(firstIdOfFirstPage, page.rows.firstOrNull()?.contentId)
            if (firstIdOfFirstPage == null) firstIdOfFirstPage = page.rows.firstOrNull()?.contentId

            if (page.rows.isNotEmpty()) {
                dao.upsertAll(page.rows)
                added += page.rows.size
                fetched += page.rows.size
            }

            // Finished when the shelf is smaller than a page, or when the panel has just proved it
            // ignores the offset and has handed back the same first row twice.
            //
            // Measured on what the panel *sent*, not on what survived filtering: a full page with a
            // few untitled rows dropped would otherwise look like a short page and end the shelf
            // early, leaving everything after it unindexed with nothing to show for it.
            val finished = page.received < pageSize || !honoursPaging
            dao.saveProgress(
                IndexProgressEntity(
                    scope = scope,
                    nextOffset = if (finished) fetched else offset + page.received,
                    indexed = fetched,
                    complete = finished,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            if (finished) return added

            // Advance by what the panel actually returned, not by the page size requested. A panel
            // that quietly caps its page would otherwise skip the titles in between.
            offset += page.received
        }
    }

    /**
     * One page from the panel: how many titles it sent, and the indexable ones among them.
     *
     * Both counts, because they answer different questions. [received] is what decides whether the
     * shelf has run out; [rows] is what gets stored.
     */
    private class Page(val received: Int, val rows: List<SearchIndexEntity>)

    /**
     * Whether the panel is honouring `start`, decided by whether the second page starts where the
     * first one did.
     *
     * Null on the first page, where there is nothing to compare against and the answer is "assume
     * yes" - which is the safe direction, because a panel that does page will be walked fully.
     *
     * **This guard is not hypothetical.** The provider tested returns byte-identical rows for
     * `start=0`, `start=5` and `start=500` on the same category: `limit` and `start` are discarded
     * and the whole shelf comes back every time. Without this check, a shelf of 500 or more titles
     * that parsed successfully would loop forever, re-inserting the same rows and never finishing.
     * It only escaped that before because such shelves were also large enough to be truncated by the
     * panel and skipped - the loop was never exercised, just never triggered.
     */
    private fun pagingIsHonoured(firstIdOfFirstPage: Int?, firstIdOfThisPage: Int?): Boolean {
        if (firstIdOfFirstPage == null) return true
        if (firstIdOfThisPage == null) return true
        return firstIdOfFirstPage != firstIdOfThisPage
    }

    private suspend fun indexSeriesCategory(category: VodCategory): Int =
        indexShelf(category, ContentKind.SERIES) { offset ->
            val page = retrying(label = "index series[${category.id}]@$offset") {
                vodApi.series(categoryId = category.id, limit = pageSize, start = offset)
            }.getOrThrow()
            Page(page.size, page.mapNotNull { it.toSearchRow(category, ContentKind.SERIES) })
        }

    private suspend fun indexMovieCategory(category: VodCategory): Int =
        indexShelf(category, ContentKind.MOVIE) { offset ->
            val page = retrying(label = "index movies[${category.id}]@$offset") {
                vodApi.vodStreams(categoryId = category.id, limit = pageSize, start = offset)
            }.getOrThrow()
            Page(page.size, page.mapNotNull { it.toSearchRow(category, ContentKind.MOVIE) })
        }

    /**
     * One film as an index row, or null when the panel sent something unusable.
     *
     * The DTO is mapped twice - once for the id, once for the rest - rather than sharing a helper
     * with [SeriesDto.toSearchRow]. Three extracted lambdas to avoid repeating six field
     * assignments is the kind of cleverness that costs more to read than it saves.
     */
    private fun VodStreamDto.toSearchRow(
        category: VodCategory,
        kind: ContentKind,
    ): SearchIndexEntity? {
        val film = toEntity(sortOrder = 0)?.toModel() ?: return null
        if (film.name.isBlank()) return null
        return SearchIndexEntity(
            key = indexKey(kind, film.id),
            kind = kind.name,
            contentId = film.id,
            name = film.name,
            nameLower = film.name.lowercase(),
            subtitle = category.name,
            categoryId = category.id,
            posterUrl = film.posterUrl,
            iconUrl = null,
        )
    }

    /** As [VodStreamDto.toSearchRow], for a series. */
    private fun SeriesDto.toSearchRow(
        category: VodCategory,
        kind: ContentKind,
    ): SearchIndexEntity? {
        val show = toEntity(sortOrder = 0)?.toModel() ?: return null
        if (show.name.isBlank()) return null
        return SearchIndexEntity(
            key = indexKey(kind, show.id),
            kind = kind.name,
            contentId = show.id,
            name = show.name,
            nameLower = show.name.lowercase(),
            subtitle = category.name,
            categoryId = category.id,
            posterUrl = show.posterUrl,
            iconUrl = null,
        )
    }

    private fun SearchIndexEntity.toHit() = SearchHit(
        kind = runCatching { ContentKind.valueOf(kind) }.getOrDefault(ContentKind.MOVIE),
        contentId = contentId,
        name = name,
        subtitle = subtitle,
        categoryId = categoryId,
        posterUrl = posterUrl,
        iconUrl = iconUrl,
    )

    private companion object {
        const val TAG = "4J"
        const val DEFAULT_LIMIT = 60

        /**
         * Film category ids on this panel begin "Movies", series begin "Series".
         *
         * A record of the provider's own naming, not a guess: this panel's catalogue returned
         * categories literally called "Movies-New Releases" and "Series-Drama" in one combined list.
         * It only decides which endpoint to page, and a category matching neither is treated as a
         * film shelf - the safe direction, since a film shelf still returns titles, whereas paging
         * the wrong series endpoint would silently index nothing.
         */
        const val SERIES_CATEGORY_PREFIX = "Series"
    }
}

internal fun indexKey(kind: ContentKind, id: Int): String = "${kind.name}:$id"

internal fun indexScope(kind: ContentKind, categoryId: String): String = "${kind.name}:$categoryId"

/**
 * Prepare a typed term for the index query.
 *
 * Trimmed and lower-cased, and nothing else. Accents are kept deliberately: a provider carries
 * accented titles and someone searching for one types the accent, so folding to ASCII would make
 * the search work for a pasted title and fail for the person actually typing it.
 *
 * The wildcards a `LIKE` pattern would interpret are left in place rather than escaped, because
 * the query binds the term as a parameter and wraps it in its own `%`s - see the DAO. Escaping here
 * as well would make a literal `%` unsearchable, which is its own small wrongness.
 */
internal fun normaliseSearchTerm(raw: String): String = raw.trim().lowercase()

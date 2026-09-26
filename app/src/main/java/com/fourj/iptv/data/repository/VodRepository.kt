package com.fourj.iptv.data.repository

import android.util.Log
import com.fourj.iptv.data.local.EpisodeEntity
import com.fourj.iptv.data.local.MovieEntity
import com.fourj.iptv.data.local.replaceMoviesInCategory
import com.fourj.iptv.data.local.replaceSeriesInCategory
import com.fourj.iptv.data.local.SeriesEntity
import com.fourj.iptv.data.local.VodCategoryEntity
import com.fourj.iptv.data.local.VodDatabase
import com.fourj.iptv.data.remote.retrying
import com.fourj.iptv.data.remote.runCatchingCancellable
import com.fourj.iptv.data.remote.StreamUrls
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.data.remote.xtream.SeriesDto
import com.fourj.iptv.data.remote.xtream.SeriesInfoResponse
import com.fourj.iptv.data.remote.xtream.VodApi
import com.fourj.iptv.data.remote.xtream.VodCategoryDto
import com.fourj.iptv.data.remote.xtream.VodStreamDto
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.Episode
import com.fourj.iptv.domain.model.Favourite
import com.fourj.iptv.domain.model.Movie
import com.fourj.iptv.domain.model.PlaybackProgress
import com.fourj.iptv.domain.model.ProviderProfile
import com.fourj.iptv.domain.model.Season
import com.fourj.iptv.domain.model.Series
import com.fourj.iptv.domain.model.VodCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * On-demand catalogue and library.
 *
 * Cached the same way live TV is: categories always, contents per category on demand, because a
 * film library is far too large to fetch in one call and panels truncate big responses.
 */
class VodRepository(
    private val profile: ProviderProfile,
    private val database: VodDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val api: VodApi,
) {

    suspend fun refreshVodCategories(): Result<List<VodCategory>> = withContext(ioDispatcher) {
        retrying(label = "get_vod_categories") { api.vodCategories() }
            .map { categories ->
                val rows = categories.mapIndexedNotNull { index, dto ->
                    dto.toEntity(index, ContentKind.MOVIE.name)
                }
                database.vodDao().upsertVodCategories(rows)
                rows.map { VodCategory(it.categoryId, it.categoryName) }
            }
    }

    suspend fun refreshSeriesCategories(): Result<List<VodCategory>> = withContext(ioDispatcher) {
        retrying(label = "get_series_categories") { api.seriesCategories() }
            .map { categories ->
                // Films and series are separate namespaces but a panel may return them in one
                // list, so each is recorded under its own kind rather than inferred later.
                val rows = categories.mapIndexedNotNull { index, dto ->
                    dto.toEntity(index, ContentKind.SERIES.name)
                }
                database.vodDao().upsertVodCategories(rows)
                rows.map { VodCategory(it.categoryId, it.categoryName) }
            }
    }

    fun observeVodCategories(kind: ContentKind): Flow<List<VodCategory>> =
        database.vodDao().observeVodCategories(kind.name)
            .map { rows -> rows.map { VodCategory(it.categoryId, it.categoryName) } }
            .flowOn(ioDispatcher)

    /**
     * Load a category's contents, replacing what was cached for it.
     *
     * Films and series are fetched separately because they are separate namespaces, even though a
     * panel may present them under one category list. Only the table for [kind] is touched: the two
     * namespaces can share a category id, so clearing both would empty a series shelf because a
     * film shelf was refreshed.
     *
     * The result is authoritative, so a film withdrawn at the provider disappears from the grid
     * rather than lingering forever. An empty response therefore clears the category - a genuine
     * "this shelf is now empty" is different from a failed fetch, and only the latter throws.
     */
    suspend fun ensureCategoryLoaded(categoryId: String, kind: ContentKind): Result<Unit> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                if (kind == ContentKind.MOVIE) {
                    val rows = retrying(label = "get_vod_streams[$categoryId]") {
                        api.vodStreams(categoryId = categoryId)
                    }.getOrThrow().mapIndexedNotNull { index, dto -> dto.toEntity(index) }
                    database.replaceMoviesInCategory(categoryId, rows)
                } else {
                    val rows = retrying(label = "get_series[$categoryId]") {
                        api.series(categoryId = categoryId)
                    }.getOrThrow().mapIndexedNotNull { index, dto -> dto.toEntity(index) }
                    database.replaceSeriesInCategory(categoryId, rows)
                }
            }
        }

    fun observeMovies(categoryId: String): Flow<List<Movie>> =
        database.vodDao().observeMovies(categoryId)
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    fun observeSeries(categoryId: String): Flow<List<Series>> =
        database.vodDao().observeSeries(categoryId)
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    suspend fun findMovie(movieId: Int): Movie? = withContext(ioDispatcher) {
        database.vodDao().findMovie(movieId)?.toModel()
    }

    suspend fun findSeries(seriesId: Int): Series? = withContext(ioDispatcher) {
        database.vodDao().findSeries(seriesId)?.toModel()
    }

    /** Seasons and episodes for a series, cached so revisiting does not re-fetch. */
    suspend fun loadSeriesDetail(seriesId: Int): Result<List<Season>> = withContext(ioDispatcher) {
        runCatchingCancellable {
            val raw = retrying(label = "get_series_info[$seriesId]") {
                api.seriesInfo(seriesId = seriesId)
            }.getOrThrow()
            val response: SeriesInfoResponse =
                XtreamNetwork.json.decodeFromJsonElement(raw)

            val seasons = response.seasons
            val rows = seasons.flatMap { season ->
                val number = season.episodes.firstNotNullOfOrNull { it.season }
                    ?: season.id?.toIntOrNull()
                    ?: 0
                season.episodes.mapNotNull { it.toEntity(seriesId, number) }
            }
            val returned = seasons.sumOf { it.episodes.size }
            Log.i(
                TAG,
                "series $seriesId: shape=${response.shape()} ${seasons.size} season(s), " +
                    "$returned episode(s); stored ${rows.size}",
            )
            // When nothing came back, name the keys that were actually present. Two panels have
            // now nested this differently, and a count of zero says nothing about why - it only
            // says where to look next.
            if (rows.isEmpty()) {
                Log.w(TAG, "series $seriesId: no episodes read. ${describeSeriesShape(raw)}")
                // A window on the episodes section specifically. A prefix is all `seasons` on a
                // large series, which is the least interesting part; the question is always
                // whether the episodes carry a stream at all.
                val text = raw.toString()
                val at = text.indexOf("\"episodes\"")
                val window = if (at >= 0) {
                    text.substring(at, minOf(text.length, at + RAW_PREFIX))
                } else {
                    "(no episodes key)"
                }
                Log.w(TAG, "series $seriesId episodes: $window")
            }
            if (returned > 0 && rows.isEmpty()) {
                Log.w(
                    TAG,
                    "series $seriesId: dropped all $returned episode(s) - no id or info_hash on any",
                )
            }
            if (rows.isNotEmpty()) {
                database.vodDao().clearEpisodes(seriesId)
                database.vodDao().upsertEpisodes(rows)
            }
            rows.groupBy { it.seasonNumber }
                .map { (number, eps) ->
                    Season(
                        id = "$seriesId:$number",
                        number = number,
                        name = "Season $number",
                        posterUrl = null,
                    )
                }
                .sortedBy { it.number }
        }
    }

    fun observeEpisodes(seriesId: Int, seasonNumber: Int): Flow<List<Episode>> =
        database.vodDao().observeEpisodesInSeason(seriesId, seasonNumber)
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    suspend fun cachedSeasons(seriesId: Int): List<Season> = withContext(ioDispatcher) {
        database.vodDao().episodesFor(seriesId)
            .groupBy { it.seasonNumber }
            .keys
            .sorted()
            .map { Season(id = "$seriesId:$it", number = it, name = "Season $it", posterUrl = null) }
    }

    suspend fun cachedEpisodes(seriesId: Int, seasonNumber: Int): List<Episode> =
        withContext(ioDispatcher) {
            database.vodDao().episodesFor(seriesId)
                .filter { it.seasonNumber == seasonNumber }
                .map { it.toModel() }
        }

    fun observeContinueWatching(): Flow<List<PlaybackProgress>> =
        database.libraryDao().observeAllProgress()
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    suspend fun progressFor(kind: ContentKind, contentId: Int): PlaybackProgress? =
        withContext(ioDispatcher) {
            database.libraryDao().progressFor(contentKey(kind, contentId))?.toModel()
        }

    suspend fun progressForKey(key: String): PlaybackProgress? =
        withContext(ioDispatcher) { database.libraryDao().progressFor(key)?.toModel() }

    /** The episode behind a resume row, or null if it is no longer cached. */
    suspend fun findEpisode(episodeRowKey: String): Episode? = withContext(ioDispatcher) {
        database.vodDao().findEpisode(episodeRowKey)?.toModel()
    }

    suspend fun saveProgress(progress: PlaybackProgress) = withContext(ioDispatcher) {
        database.libraryDao().saveProgress(progress.toEntity())
    }

    suspend fun clearProgress(kind: ContentKind, contentId: Int) = withContext(ioDispatcher) {
        database.libraryDao().clearProgress(contentKey(kind, contentId))
    }

    fun observeFavourites(): Flow<List<Favourite>> =
        database.libraryDao().observeFavourites()
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    suspend fun favouriteFor(contentKey: String): Favourite? =
        withContext(ioDispatcher) { database.libraryDao().favouriteFor(contentKey)?.toModel() }

    suspend fun addFavourite(favourite: Favourite) = withContext(ioDispatcher) {
        database.libraryDao().addFavourite(favourite.toEntity())
    }

    suspend fun removeFavourite(contentKey: String) = withContext(ioDispatcher) {
        database.libraryDao().removeFavourite(contentKey)
    }

    suspend fun isFavourite(kind: ContentKind, contentId: Int): Boolean =
        withContext(ioDispatcher) {
            database.libraryDao().favouriteFor(contentKey(kind, contentId)) != null
        }

    suspend fun toggleFavourite(favourite: Favourite) = withContext(ioDispatcher) {
        val key = favourite.contentKey
        if (database.libraryDao().favouriteFor(key) != null) {
            database.libraryDao().removeFavourite(key)
        } else {
            database.libraryDao().addFavourite(favourite.toEntity())
        }
    }

    fun movieStreamUrl(movie: Movie): String =
        movie.directSource?.takeIf { it.startsWith("http") }
            ?: StreamUrls.movie(profile, movie)

    fun episodeStreamUrl(episode: Episode): String? = episode.sourceUrl
}

// ---------------------------------------------------------------------------------------------
// Mapping
// ---------------------------------------------------------------------------------------------

internal fun VodCategoryDto.toEntity(sortOrder: Int, kind: String): VodCategoryEntity? {
    val id = categoryId.trim()
    val name = categoryName.trim()
    if (id.isEmpty() || name.isEmpty()) return null
    return VodCategoryEntity(categoryId = id, categoryName = name, kind = kind, sortOrder = sortOrder)
}

/**
 * Series categories share the films' table but not their type.
 *
 * They are separate namespaces in the panel and can reuse the same numeric ids, so they are kept
 * under their own ids and the UI labels which is which.
 */
internal fun com.fourj.iptv.data.remote.xtream.SeriesCategoryDto.toEntity(
    sortOrder: Int,
    kind: String = ContentKind.SERIES.name,
): VodCategoryEntity? {
    val id = categoryId.trim()
    val name = categoryName.trim()
    if (id.isEmpty() || name.isEmpty()) return null
    return VodCategoryEntity(categoryId = id, categoryName = name, kind = kind, sortOrder = sortOrder)
}

internal fun VodStreamDto.toEntity(sortOrder: Int): MovieEntity? {
    if (streamId == 0) return null
    val label = name.trim()
    if (label.isEmpty()) return null
    return MovieEntity(
        movieId = streamId,
        name = label,
        categoryId = categoryId?.trim()?.takeIf { it.isNotEmpty() },
        posterUrl = (streamIcon ?: movieImage)?.trim()?.takeIf { it.isNotEmpty() },
        backdropUrl = backdropPath?.firstOrNull { it.isNotBlank() },
        containerExtension = containerExtension?.trim()?.takeIf { it.isNotEmpty() },
        directSource = directSource?.trim()?.takeIf { it.isNotEmpty() },
        httpUserAgent = httpUserAgent?.trim()?.takeIf { it.isNotEmpty() },
        httpReferrer = httpReferrer?.trim()?.takeIf { it.isNotEmpty() },
        rating = rating5 ?: rating?.toDoubleOrNull(),
        plot = null,
        durationSeconds = null,
        sortOrder = sortOrder,
    )
}

internal fun SeriesDto.toEntity(sortOrder: Int): SeriesEntity? {
    if (seriesId == 0) return null
    val label = name.trim()
    if (label.isEmpty()) return null
    return SeriesEntity(
        seriesId = seriesId,
        name = label,
        categoryId = categoryId?.trim()?.takeIf { it.isNotEmpty() },
        posterUrl = cover?.trim()?.takeIf { it.isNotEmpty() },
        plot = plot?.trim()?.takeIf { it.isNotEmpty() },
        cast = cast?.trim()?.takeIf { it.isNotEmpty() },
        director = director?.trim()?.takeIf { it.isNotEmpty() },
        genre = genre?.trim()?.takeIf { it.isNotEmpty() },
        releaseDate = releaseDate?.trim()?.takeIf { it.isNotEmpty() },
        rating = rating5 ?: rating?.toDoubleOrNull(),
        sortOrder = sortOrder,
    )
}

internal fun com.fourj.iptv.data.remote.xtream.EpisodeDto.toEntity(
    seriesId: Int,
    seasonNumber: Int,
): EpisodeEntity? {
    val key = infoHash?.takeIf { it.isNotBlank() } ?: id.takeIf { it.isNotBlank() } ?: return null
    return EpisodeEntity(
        episodeRowKey = "$seriesId:$key",
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNum ?: 0,
        title = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Episode ${episodeNum ?: 0}",
        containerExtension = containerExtension?.trim()?.takeIf { it.isNotEmpty() },
        // The stream lives under `streams.direct.source` in the common form, and in a flat
        // `direct_source` in the TMDB-shaped one. Checked in that order because a panel sending
        // the second has been seen leaving the first present but empty.
        sourceUrl = streams?.direct?.source?.trim()?.takeIf { it.isNotEmpty() }
            ?: directSource?.trim()?.takeIf { it.isNotEmpty() },
        mimeType = streams?.direct?.mimeType,
        durationSeconds = null,
    )
}

internal fun MovieEntity.toModel() = Movie(
    id = movieId,
    name = name,
    categoryId = categoryId,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    containerExtension = containerExtension,
    directSource = directSource,
    httpUserAgent = httpUserAgent,
    httpReferrer = httpReferrer,
    rating = rating,
    plot = plot,
    durationSeconds = durationSeconds,
)

internal fun SeriesEntity.toModel() = Series(
    id = seriesId,
    name = name,
    categoryId = categoryId,
    posterUrl = posterUrl,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
)

internal fun EpisodeEntity.toModel() = Episode(
    id = episodeRowKey,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    containerExtension = containerExtension,
    sourceUrl = sourceUrl,
    mimeType = mimeType,
    durationSeconds = durationSeconds,
)

internal fun PlaybackProgress.toEntity() = com.fourj.iptv.data.local.PlaybackProgressEntity(
    contentKey = contentKey,
    kind = kind.name,
    contentId = contentId,
    title = title,
    subtitle = subtitle,
    positionSeconds = positionSeconds,
    durationSeconds = durationSeconds,
    posterUrl = posterUrl,
    updatedAtMillis = updatedAtMillis,
)

internal fun com.fourj.iptv.data.local.PlaybackProgressEntity.toModel() = PlaybackProgress(
    contentKey = contentKey,
    kind = runCatching { ContentKind.valueOf(kind) }.getOrDefault(ContentKind.MOVIE),
    title = title,
    subtitle = subtitle,
    positionSeconds = positionSeconds,
    durationSeconds = durationSeconds,
    posterUrl = posterUrl,
    updatedAtMillis = updatedAtMillis,
    contentId = contentId,
)

internal fun Favourite.toEntity() = com.fourj.iptv.data.local.FavouriteEntity(
    contentKey = contentKey,
    kind = kind.name,
    contentId = contentId,
    name = name,
    subtitle = subtitle,
    posterUrl = posterUrl,
    addedAtMillis = addedAtMillis,
)

internal fun com.fourj.iptv.data.local.FavouriteEntity.toModel() = Favourite(
    contentKey = contentKey,
    kind = runCatching { ContentKind.valueOf(kind) }.getOrDefault(ContentKind.MOVIE),
    contentId = contentId,
    name = name,
    subtitle = subtitle,
    posterUrl = posterUrl,
    addedAtMillis = addedAtMillis,
)

/**
 * Stable key for a piece of content.
 *
 * The kind is part of the key because live channels, films and episodes all use bare numeric ids
 * that overlap freely, and without it a film of id 101 and a live channel of id 101 would share
 * a favourite or a resume position.
 */
internal fun contentKey(kind: ContentKind, contentId: Int): String = "${kind.name}:$contentId"

/** Which of the known response layouts this panel used, for the log. */
internal fun com.fourj.iptv.data.remote.xtream.SeriesInfoResponse.shape(): String = when {
    episodes is kotlinx.serialization.json.JsonObject &&
        (episodes as kotlinx.serialization.json.JsonObject).containsKey("season") -> "nested"
    episodes is kotlinx.serialization.json.JsonObject &&
        (episodes as kotlinx.serialization.json.JsonObject).isNotEmpty() -> "keyed-by-season"
    topLevelSeasons?.isNotEmpty() == true -> "summaries-only"
    else -> "unrecognised"
}

/**
 * A one-line description of where this payload keeps its seasons and episodes.
 *
 * Diagnostic only, and deliberately reads the raw JSON: a typed model has already discarded
 * anything it did not recognise by the time it reaches here, which is exactly the information
 * needed to work out why nothing was read. Panels disagree about this nesting, and three
 * different layouts have now turned up.
 */
internal fun describeSeriesShape(raw: kotlinx.serialization.json.JsonObject): String {
    fun kind(key: String): String = when (val v = raw[key]) {
        null -> "$key=absent"
        is kotlinx.serialization.json.JsonObject -> {
            val firstValue = v.values.filterIsInstance<kotlinx.serialization.json.JsonObject>().firstOrNull()
            buildString {
                append("$key={keys: ${v.keys.joinToString(",")}")
                if (firstValue != null) {
                    append("; first value keys: ${firstValue.keys.joinToString(",")}")
                }
                append('}')
            }
        }
        is kotlinx.serialization.json.JsonArray ->
            "$key=[${v.size} items; first=${(v.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.keys?.joinToString(",") ?: "scalar/empty"}]"
        else -> "$key=${v::class.simpleName}"
    }
    return listOf("seasons", "episodes", "info").joinToString("  ") { kind(it) }
}

/**
 * Key for an episode, which has no usable numeric id.
 *
 * The panel's episode identifier is kept verbatim so the row can be found again later.
 */
internal fun episodeContentKey(episodeRowKey: String): String = "${ContentKind.EPISODE.name}:$episodeRowKey"

private const val TAG = "4J"

/** How much of a payload to dump when nothing could be read from it. */
private const val RAW_PREFIX = 900

package com.fourj.iptv.data.repository

import com.fourj.iptv.data.local.EpisodeEntity
import com.fourj.iptv.data.local.MovieEntity
import com.fourj.iptv.data.local.SeriesEntity
import com.fourj.iptv.data.local.VodCategoryEntity
import com.fourj.iptv.data.local.VodDatabase
import com.fourj.iptv.data.remote.StreamUrls
import com.fourj.iptv.data.remote.runCatchingCancellable
import com.fourj.iptv.data.remote.retrying
import com.fourj.iptv.data.remote.xtream.SeriesDto
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
                rows.map { VodCategory(it.categoryId, it.categoryName) }
            }
    }

    suspend fun refreshSeriesCategories(): Result<List<VodCategory>> = withContext(ioDispatcher) {
        retrying(label = "get_series_categories") { api.seriesCategories() }
            .map { categories ->
                // stored under their own ids and presented as one list in the UI.
                val rows = categories.mapIndexedNotNull { index, dto ->
                    dto.toEntity(index, ContentKind.SERIES.name)
                }
                rows.map { VodCategory(it.categoryId, it.categoryName) }
            }
    }

    fun observeVodCategories(kind: ContentKind): Flow<List<VodCategory>> =
        database.vodDao().observeVodCategories(kind.name)
            .map { rows -> rows.map { VodCategory(it.categoryId, it.categoryName) } }
            .flowOn(ioDispatcher)

    /**
     * Load a category's films and series.
     *
     * Both are fetched because a panel's categories are not cleanly separated, and a category
     * that returns nothing from one call and plenty from the other is common.
     */
    suspend fun ensureCategoryLoaded(categoryId: String, kind: ContentKind): Result<Unit> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                if (kind == ContentKind.MOVIE) {
                    val rows = retrying(label = "get_vod_streams[$categoryId]") {
                        api.vodStreams(categoryId = categoryId)
                    }.getOrThrow().mapIndexedNotNull { index, dto -> dto.toEntity(index) }
                    if (rows.isNotEmpty()) {
                        database.vodDao().upsertMovies(rows)
                    }
                } else {
                    val rows = retrying(label = "get_series[$categoryId]") {
                        api.series(categoryId = categoryId)
                    }.getOrThrow().mapIndexedNotNull { index, dto -> dto.toEntity(index) }
                    if (rows.isNotEmpty()) {
                        database.vodDao().upsertSeries(rows)
                    }
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
            val response = retrying(label = "get_series_info[$seriesId]") {
                api.seriesInfo(seriesId = seriesId)
            }.getOrThrow()

            val rows = response.episodes?.seasons.orEmpty().flatMap { season ->
                val number = season.episodes.firstNotNullOfOrNull { it.season }
                    ?: season.id?.toIntOrNull()
                    ?: 0
                season.episodes.mapNotNull { it.toEntity(seriesId, number) }
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
        sourceUrl = streams?.direct?.source?.trim()?.takeIf { it.isNotEmpty() },
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
    contentKey = contentKey(kind, contentId),
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
    contentId = contentId,
    kind = runCatching { ContentKind.valueOf(kind) }.getOrDefault(ContentKind.MOVIE),
    title = title,
    subtitle = subtitle,
    positionSeconds = positionSeconds,
    durationSeconds = durationSeconds,
    posterUrl = posterUrl,
    updatedAtMillis = updatedAtMillis,
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



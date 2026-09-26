package com.fourj.iptv.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An on-demand category.
 *
 * [kind] is recorded at fetch time rather than inferred from the name. A real provider was
 * observed returning films and series in one combined list, distinguished only by names like
 * "Movies-New Releases" against "Series-Drama" - so without a stored kind the Movies tab could
 * select a series category and show nothing at all.
 *
 * The key is (id, kind) and not the id alone, because the two namespaces draw on the same pool of
 * numbers. With the id alone, a series category of 7 silently replaced a film category of 7.
 */
@Entity(tableName = "vod_categories", primaryKeys = ["categoryId", "kind"], indices = [Index("kind")])
data class VodCategoryEntity(
    val categoryId: String,
    val kind: String,
    val categoryName: String,
    val sortOrder: Int,
)

@Entity(tableName = "movies")
data class MovieEntity(
    @PrimaryKey val movieId: Int,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val containerExtension: String?,
    val directSource: String?,
    val httpUserAgent: String?,
    val httpReferrer: String?,
    val rating: Double?,
    val plot: String?,
    val durationSeconds: Long?,
    val sortOrder: Int,
)

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesId: Int,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val sortOrder: Int,
)

/**
 * Episodes are cached too, so revisiting a series does not re-fetch it.
 *
 * A single series can be hundreds of episodes across many seasons, which is far too much to hold
 * in memory, so the cache is the source of truth and the UI observes one season at a time.
 */
@Entity(
    tableName = "episodes",
    indices = [Index(value = ["seriesId", "seasonNumber"])],
)
data class EpisodeEntity(
    @PrimaryKey val episodeRowKey: String,
    val seriesId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String?,
    val sourceUrl: String?,
    val mimeType: String?,
    val durationSeconds: Long?,
)

/** One row per watched thing, whatever its kind. Drives "continue watching" and history. */
@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val contentKey: String,
    val kind: String,
    val contentId: Int,
    val title: String,
    val subtitle: String?,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val posterUrl: String?,
    val updatedAtMillis: Long,
)

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val contentKey: String,
    val kind: String,
    val contentId: Int,
    val name: String,
    val subtitle: String?,
    val posterUrl: String?,
    val addedAtMillis: Long,
)

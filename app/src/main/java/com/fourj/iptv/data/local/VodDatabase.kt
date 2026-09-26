package com.fourj.iptv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * The on-demand catalogue, series episodes, and the viewer's library.
 *
 * A separate database file from live TV. The film cache is large and disposable in a way the live
 * cache is not, and keeping them apart means a schema change to one never forces a migration on
 * the other.
 */
@Database(
    entities = [
        VodCategoryEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        PlaybackProgressEntity::class,
        FavouriteEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VodDatabase : RoomDatabase() {
    abstract fun vodDao(): VodDao
    abstract fun libraryDao(): LibraryDao
}

/**
 * Replace one category's contents, atomically.
 *
 * Room does not allow a `@Transaction` method to reach a second DAO, so this is expressed at the
 * database level. It has to be one transaction: a reader must never observe the
 * cleared-but-not-refilled state, which would empty the grid on screen mid-refresh.
 */
suspend fun VodDatabase.replaceVodCategory(
    categoryId: String,
    movies: List<MovieEntity>,
    series: List<SeriesEntity>,
) = withTransaction {
    vodDao().clearMoviesInCategory(categoryId)
    vodDao().clearSeriesInCategory(categoryId)
    if (movies.isNotEmpty()) vodDao().upsertMovies(movies)
    if (series.isNotEmpty()) vodDao().upsertSeries(series)
}

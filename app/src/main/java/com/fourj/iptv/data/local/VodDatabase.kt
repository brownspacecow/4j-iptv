package com.fourj.iptv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
    exportSchema = true,
)
abstract class VodDatabase : RoomDatabase() {
    abstract fun vodDao(): VodDao
    abstract fun libraryDao(): LibraryDao
}

/**
 * v1 -> v2: `vod_categories` gained a `kind` column and a composite primary key.
 *
 * The table is dropped and rebuilt rather than altered. It holds nothing but a cache of the
 * provider's own category list, which is re-fetched on the next launch, so there is nothing here
 * worth preserving - and `ALTER TABLE` cannot add a column to a primary key in SQLite anyway.
 *
 * Favourites and playback progress are separate tables and are deliberately left alone: those are
 * the viewer's own data, not a cache, and losing them to a schema change would be a real loss.
 *
 * Named apart from the live database's own `MIGRATION_1_2` on purpose. Two migrations with the same
 * name in one module is a trap: it is only distinguishable by an import alias, and the wrong one
 * silently fails to apply.
 */
val VOD_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `vod_categories`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vod_categories` (
                `categoryId` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `categoryName` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                PRIMARY KEY(`categoryId`, `kind`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_categories_kind` ON `vod_categories` (`kind`)")
    }
}

/**
 * v2 -> v3: `episodes` gained `streamId`, the panel's own episode id.
 *
 * An additive column, so the existing rows are kept. They will have a null `streamId` and are
 * treated as unplayable until the series is loaded again, which repopulates them - the catalogue
 * is a cache and refetching one series is cheap, which is a far better trade than clearing the
 * viewer's progress and favourites to satisfy a schema change.
 */
val VOD_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `episodes` ADD COLUMN `streamId` TEXT")
    }
}

/**
 * Replace one category's films, atomically.
 *
 * It has to be one transaction: a reader must never observe the cleared-but-not-refilled state,
 * which would empty the grid on screen mid-refresh. Room does not allow a `@Transaction` method to
 * reach a second DAO, so this is expressed at the database level.
 */
suspend fun VodDatabase.replaceMoviesInCategory(
    categoryId: String,
    movies: List<MovieEntity>,
) = withTransaction {
    vodDao().clearMoviesInCategory(categoryId)
    if (movies.isNotEmpty()) vodDao().upsertMovies(movies)
}

/** As [replaceMoviesInCategory], for series. The two tables are cleared independently. */
suspend fun VodDatabase.replaceSeriesInCategory(
    categoryId: String,
    series: List<SeriesEntity>,
) = withTransaction {
    vodDao().clearSeriesInCategory(categoryId)
    if (series.isNotEmpty()) vodDao().upsertSeries(series)
}

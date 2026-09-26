package com.fourj.iptv.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.fourj.iptv.domain.model.ContentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The on-demand database's upgrade path from v1 to v2.
 *
 * The migrated file is then opened with Room itself, so this checks two things at once: that the
 * viewer's own data survives, and that the schema the migration produces is genuinely the one the
 * entities describe. A migration that runs without error but leaves the wrong columns or the wrong
 * primary key passes a "did it throw?" test and fails this one.
 */
@RunWith(RobolectricTestRunner::class)
class VodMigrationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val fileName = "vod-migration-test.db"

    @Before
    fun removeDatabase() {
        context.deleteDatabase(fileName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(fileName)
    }

    /** Build the v1 file by hand: the shape the app shipped with, and nothing more. */
    private fun createVersion1Database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vod_categories` (
                        `categoryId` TEXT NOT NULL, `categoryName` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`categoryId`))
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `movies` (
                        `movieId` INTEGER NOT NULL, `name` TEXT NOT NULL, `categoryId` TEXT,
                        `posterUrl` TEXT, `backdropUrl` TEXT, `containerExtension` TEXT,
                        `directSource` TEXT, `httpUserAgent` TEXT, `httpReferrer` TEXT,
                        `rating` REAL, `plot` TEXT, `durationSeconds` INTEGER,
                        `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`movieId`))
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `series` (
                        `seriesId` INTEGER NOT NULL, `name` TEXT NOT NULL, `categoryId` TEXT,
                        `posterUrl` TEXT, `plot` TEXT, `cast` TEXT, `director` TEXT, `genre` TEXT,
                        `releaseDate` TEXT, `rating` REAL, `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`seriesId`))
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episodes` (
                        `episodeRowKey` TEXT NOT NULL, `seriesId` INTEGER NOT NULL,
                        `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL,
                        `title` TEXT NOT NULL, `containerExtension` TEXT, `sourceUrl` TEXT,
                        `mimeType` TEXT, `durationSeconds` INTEGER,
                        PRIMARY KEY(`episodeRowKey`))
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_episodes_seriesId_seasonNumber` " +
                        "ON `episodes` (`seriesId`, `seasonNumber`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favourites` (
                        `contentKey` TEXT NOT NULL, `kind` TEXT NOT NULL,
                        `contentId` INTEGER NOT NULL, `name` TEXT NOT NULL, `subtitle` TEXT,
                        `posterUrl` TEXT, `addedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`contentKey`))
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_progress` (
                        `contentKey` TEXT NOT NULL, `kind` TEXT NOT NULL,
                        `contentId` INTEGER NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT,
                        `positionSeconds` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL,
                        `posterUrl` TEXT, `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`contentKey`))
                    """.trimIndent(),
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(fileName)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun upgradeToV2() {
        val db = createVersion1Database()
        db.execSQL("INSERT INTO vod_categories VALUES ('10','Old Films',0)")
        db.execSQL(
            "INSERT INTO favourites (contentKey,kind,contentId,name,addedAtMillis) " +
                "VALUES ('MOVIE:1001','MOVIE',1001,'A Favourite',1700000000000)",
        )
        db.execSQL(
            "INSERT INTO playback_progress " +
                "(contentKey,kind,contentId,title,positionSeconds,durationSeconds,updatedAtMillis) " +
                "VALUES ('MOVIE:1001','MOVIE',1001,'A Film',600,2400,1700000000000)",
        )
        VOD_MIGRATION_1_2.migrate(db)
        db.version = 2
        db.close()
    }

    /**
     * Room re-checks the whole schema on open, so a successful open here means the migration
     * produced exactly the tables, columns and keys the entities declare.
     */
    private fun openMigrated(): VodDatabase =
        Room.databaseBuilder(context, VodDatabase::class.java, fileName)
            .addMigrations(VOD_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `a migrated database opens and keeps the viewers own data`() = runTest {
        upgradeToV2()

        val db = openMigrated()
        try {
            val favourites = db.libraryDao().observeFavourites().first()
            assertEquals(1, favourites.size)
            assertEquals("A Favourite", favourites.single().name)
            // Stored as text on purpose, so a kind added in a later release still reads back; the
            // mapping to the enum is the repository's job, not the row's.
            assertEquals(ContentKind.MOVIE.name, favourites.single().kind)

            val progress = db.libraryDao().progressFor("MOVIE:1001")
            assertEquals(600L, progress!!.positionSeconds)
            assertEquals(2400L, progress.durationSeconds)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the category cache is dropped because it can be refetched`() = runTest {
        upgradeToV2()

        val db = openMigrated()
        try {
            // A cache of the provider's own category list: re-fetched on next launch, so nothing is
            // lost by clearing it. Clearing it is the point - the old shape has a single-column
            // primary key and cannot be altered into the new one.
            assertEquals(emptyList<VodCategoryEntity>(), db.vodDao().observeVodCategories("MOVIE").first())
        } finally {
            db.close()
        }
    }

    @Test
    fun `categories in different kinds can share an id after migrating`() = runTest {
        upgradeToV2()

        val db = openMigrated()
        try {
            // The reason the key is (id, kind): with the id alone, one of these rows would silently
            // replace the other and a shelf would appear empty.
            db.vodDao().upsertVodCategories(
                listOf(
                    VodCategoryEntity(categoryId = "7", kind = "MOVIE", categoryName = "Films", sortOrder = 0),
                    VodCategoryEntity(categoryId = "7", kind = "SERIES", categoryName = "Shows", sortOrder = 0),
                ),
            )
            assertEquals(1, db.vodDao().observeVodCategories("MOVIE").first().size)
            assertEquals(1, db.vodDao().observeVodCategories("SERIES").first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `favourites and progress survive with their kinds intact`() = runTest {
        upgradeToV2()

        val db = openMigrated()
        try {
            // Kinds are stored as text, so a bad migration could leave them unreadable and every
            // favourite would fall back to MOVIE.
            db.libraryDao().addFavourite(
                FavouriteEntity(
                    contentKey = "SERIES:3001",
                    kind = ContentKind.SERIES.name,
                    contentId = 3001,
                    name = "A Show",
                    subtitle = null,
                    posterUrl = null,
                    addedAtMillis = 1L,
                ),
            )
            val stored = db.libraryDao().favouriteFor("SERIES:3001")!!
            assertEquals(ContentKind.SERIES.name, stored.kind)
        } finally {
            db.close()
        }
    }
}

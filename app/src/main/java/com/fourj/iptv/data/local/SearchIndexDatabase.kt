package com.fourj.iptv.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One searchable thing: a live channel, a film, or a series.
 *
 * **A separate database file, and deliberately disposable.** Everything in here is derived - it is a
 * copy of names the provider already gave us - so the database can be deleted and rebuilt at any
 * time without losing anything a viewer would miss. That is what makes it safe to change the schema
 * without writing a migration: there is no user data in the file to lose, and a stale index is
 * rebuilt rather than migrated.
 *
 * The live and VOD caches could not be treated this way. They sit alongside favourites and playback
 * progress, and those are the viewer's own data.
 */
@Entity(
    tableName = "search_index",
    indices = [Index("nameLower"), Index("kind")],
)
data class SearchIndexEntity(
    /**
     * `KIND:id`, e.g. `MOVIE:3054675`.
     *
     * Composite because the panel draws live channels, films and series from the same pool of
     * numbers, so a film of id 7 and a series of id 7 both really exist. Keying on the id alone
     * would make them overwrite each other and one of them would silently vanish from search.
     */
    @PrimaryKey val key: String,
    val kind: String,
    val contentId: Int,
    val name: String,
    /**
     * Lower-cased name, stored rather than computed per query.
     *
     * Search has to be case-insensitive, and `LIKE` in SQLite is only case-insensitive for ASCII.
     * Lower-casing in Kotlin instead makes it correct for every title, including the accented ones
     * a real provider carries, and an index on this column is what a prefix query can use.
     */
    val nameLower: String,
    /** Category name, or the year - the second line under a result. */
    val subtitle: String?,
    val categoryId: String?,
    val posterUrl: String?,
    val iconUrl: String?,
)

/**
 * How far the background indexer has got with one category, so it can resume rather than restart.
 *
 * The key is `KIND:categoryId` rather than the kind alone. A single category can hold more titles
 * than a page returns, so "done" is a per-category fact, and tracking progress per kind would lose
 * the place in the middle of the one large category that dominates the run.
 *
 * [nextOffset] is the `start` the next request should ask for. Persisting it is what makes the job
 * resumable across an app restart - without it, closing the app mid-index throws away the work and
 * the next launch re-downloads from the start, which on a catalogue this size is minutes of the
 * viewer's data connection.
 */
@Entity(tableName = "index_progress")
data class IndexProgressEntity(
    @PrimaryKey val scope: String,
    val nextOffset: Int,
    val indexed: Int,
    val complete: Boolean,
    val updatedAtMillis: Long,
)

@Dao
interface SearchIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SearchIndexEntity>)

    @Query("SELECT COUNT(*) FROM search_index WHERE kind = :kind")
    fun observeIndexedCount(kind: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM search_index WHERE kind = :kind")
    suspend fun indexedCount(kind: String): Int

    /**
     * Substring search, best match first.
     *
     * A `LIKE '%term%'` cannot use an index, so this is a scan. That is a deliberate trade: the
     * alternative is FTS, which would be faster on a large index but only matches whole words, and
     * someone typing "sky at" wants "The Sky at Night" - a phrase match in the middle of a title,
     * which substring matching finds and FTS does not.
     *
     * Ordered so a title *starting* with the term outranks one that merely contains it, which is
     * almost always what the person meant: searching "sky" should put "Sky Sports" above
     * "The Late Sky Show Archive".
     */
    @Query(
        """
        SELECT * FROM search_index
        WHERE nameLower LIKE '%' || :term || '%'
        ORDER BY
            CASE WHEN nameLower LIKE :term || '%' THEN 0
                 WHEN nameLower LIKE '% ' || :term || '%' THEN 1
                 ELSE 2 END,
            length(name) ASC,
            name ASC
        LIMIT :limit
        """,
    )
    suspend fun search(term: String, limit: Int): List<SearchIndexEntity>

    @Query("SELECT * FROM index_progress WHERE scope = :scope LIMIT 1")
    suspend fun progressFor(scope: String): IndexProgressEntity?

    @Query("SELECT * FROM index_progress")
    fun observeAllProgress(): Flow<List<IndexProgressEntity>>

    @Query("SELECT * FROM index_progress")
    suspend fun allProgress(): List<IndexProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(row: IndexProgressEntity)

    @Query("DELETE FROM search_index")
    suspend fun clearIndex()

    @Query("DELETE FROM index_progress")
    suspend fun clearProgress()
}

@Database(
    entities = [SearchIndexEntity::class, IndexProgressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SearchIndexDatabase : RoomDatabase() {
    abstract fun searchIndexDao(): SearchIndexDao
}

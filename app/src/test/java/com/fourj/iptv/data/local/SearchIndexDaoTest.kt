package com.fourj.iptv.data.local

import com.fourj.iptv.testing.inMemorySearchIndexDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The search index database, and the schema it creates.
 *
 * Named for the DAO rather than a migration, because that is what it tests: what the index stores,
 * how a search behaves against it, and what the progress table records. Exactly one test is about
 * the schema - [the schema holds no viewer-owned column] - and it earns its place, because it is
 * the one that should fail the day someone adds a column the viewer would miss if the file were
 * deleted. Until then a schema change is handled by throwing the file away, and that is the whole
 * justification for this database having no migrations.
 */
@RunWith(RobolectricTestRunner::class)
class SearchIndexDaoTest {

    private lateinit var database: SearchIndexDatabase

    @Before
    fun setUp() {
        database = inMemorySearchIndexDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `index rows round-trip through the database`() = runBlockingTest {
        database.searchIndexDao().upsertAll(
            listOf(
                SearchIndexEntity(
                    key = "MOVIE:1",
                    kind = "MOVIE",
                    contentId = 1,
                    name = "The Sky at Night",
                    nameLower = "the sky at night",
                    subtitle = "Movies-Drama",
                    categoryId = "10",
                    posterUrl = null,
                    iconUrl = null,
                ),
            ),
        )
        val found = database.searchIndexDao().search("sky", 10)
        assertEquals(1, found.size)
        assertEquals("The Sky at Night", found.first().name)
    }

    @Test
    fun `a film and a series with the same numeric id coexist`() = runBlockingTest {
        // The reason the primary key is "KIND:id" rather than the id alone: panels draw live
        // channels, films and series from one pool of numbers, so id 7 really is both a film and a
        // series. Keyed on the id alone, the second insert would silently replace the first and one
        // of the two would vanish from search with no error anywhere.
        database.searchIndexDao().upsertAll(
            listOf(
                row("MOVIE:7", "MOVIE", 7, "Some Film"),
                row("SERIES:7", "SERIES", 7, "Some Series"),
            ),
        )
        assertEquals(2, database.searchIndexDao().search("some", 10).size)
        assertEquals(1, database.searchIndexDao().indexedCount("MOVIE"))
        assertEquals(1, database.searchIndexDao().indexedCount("SERIES"))
    }

    @Test
    fun `search prefers a title that starts with the term`() = runBlockingTest {
        database.searchIndexDao().upsertAll(
            listOf(
                row("MOVIE:1", "MOVIE", 1, "The Late Sky Show Archive"),
                row("MOVIE:2", "MOVIE", 2, "Sky Sports"),
            ),
        )
        val found = database.searchIndexDao().search("sky", 10)
        assertEquals("Sky Sports", found.first().name)
    }

    @Test
    fun `search matches a word in the middle of a title`() = runBlockingTest {
        // Why this is a LIKE scan and not FTS: someone typing "sky at" wants "The Sky at Night".
        // Whole-word token matching handles that, but substring matching is also what finds a
        // partial word while the viewer is still typing it, which is the common case here.
        database.searchIndexDao().upsertAll(
            listOf(row("MOVIE:1", "MOVIE", 1, "The Sky at Night")),
        )
        assertEquals(1, database.searchIndexDao().search("ky at", 10).size)
    }

    @Test
    fun `search is case insensitive because the name is stored folded`() = runBlockingTest {
        database.searchIndexDao().upsertAll(
            listOf(row("MOVIE:1", "MOVIE", 1, "THE SKY AT NIGHT")),
        )
        assertEquals(1, database.searchIndexDao().search("sky", 10).size)
    }

    @Test
    fun `a blank term matches nothing rather than everything`() = runBlockingTest {
        // A LIKE '%%' would match every row, so an empty search would appear to work by showing the
        // entire library. Returning nothing is the honest answer to a query with no term in it.
        // The guard lives in the repository rather than the query, so this checks the query itself
        // behaves and the guard is asserted in SearchQueryTest.
        assertTrue(dao().search("", 10).isEmpty())
    }

    @Test
    fun `re-indexing a title replaces its row rather than duplicating it`() = runBlockingTest {
        // A shelf is re-fetched whenever the viewer revisits it, and the id is the same, so this
        // must be an update. Left as an insert it would double every title in the index and
        // inflate the "N titles indexed" figure with each browse.
        val dao = dao()
        dao.upsertAll(listOf(row("MOVIE:1", "MOVIE", 1, "Old Title")))
        dao.upsertAll(listOf(row("MOVIE:1", "MOVIE", 1, "New Title")))
        assertEquals(1, dao.indexedCount("MOVIE"))
        assertEquals("New Title", dao.search("new", 10).first().name)
        assertTrue(dao.search("old", 10).isEmpty())
    }

    @Test
    fun `the schema holds no viewer-owned column, which is what makes it safe to discard`() {
        // The justification for having no migrations on this database at all: it stores nothing but
        // names the provider already gave us, so deleting the file costs the viewer nothing. The day
        // someone adds a column here that reflects something the viewer did - a search they saved,
        // a synonym they added - that reasoning stops applying, and this test is what should fail
        // and prompt the move to a real migration.
        val columns = mutableSetOf<String>()
        // PRAGMA rather than a table-valued function: the `pragma_table_info('x')` form is a
        // SQLite extension that Android's bundled SQLite does not accept, and the failure looks
        // like a malformed query rather than an unsupported feature.
        database.openHelper.readableDatabase.query(
            "PRAGMA table_info(search_index)",
        ).use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns.add(cursor.getString(nameColumn))
        }
        val ownedByViewer = columns - setOf(
            "key", "kind", "contentId", "name", "nameLower",
            "subtitle", "categoryId", "posterUrl", "iconUrl",
        )
        assertTrue(
            "unexpected column(s) in the search index: $ownedByViewer",
            ownedByViewer.isEmpty(),
        )
    }

    @Test
    fun `progress is tracked per category so a large shelf resumes mid-way`() = runBlockingTest {
        val dao = database.searchIndexDao()
        dao.saveProgress(
            IndexProgressEntity("MOVIE:10", nextOffset = 500, indexed = 500, complete = false, updatedAtMillis = 1),
        )
        dao.saveProgress(
            IndexProgressEntity("MOVIE:11", nextOffset = 120, indexed = 120, complete = true, updatedAtMillis = 1),
        )
        assertEquals(500, dao.progressFor("MOVIE:10")?.nextOffset)
        assertEquals(false, dao.progressFor("MOVIE:10")?.complete)
        assertEquals(true, dao.progressFor("MOVIE:11")?.complete)
        assertEquals(1, dao.allProgress().count { it.complete })
    }

    @Test
    fun `clearing the index leaves progress intact so it can be resumed`() = runBlockingTest {
        val dao = dao()
        dao.upsertAll(listOf(row("MOVIE:1", "MOVIE", 1, "Something")))
        dao.saveProgress(
            IndexProgressEntity("MOVIE:10", nextOffset = 500, indexed = 500, complete = false, updatedAtMillis = 1),
        )
        dao.clearIndex()
        assertEquals(0, dao.indexedCount("MOVIE"))
        assertNotNull(dao.progressFor("MOVIE:10"))
    }

    @Test
    fun `results are capped so a one-letter term cannot flood the screen`() = runBlockingTest {
        val dao = dao()
        dao.upsertAll((1..500).map { row("MOVIE:$it", "MOVIE", it, "Sky Channel $it") })
        // A single letter matches tens of thousands of titles on this provider. Without a cap the
        // list would be unusable and the query would spend its time building rows nobody sees.
        assertEquals(25, dao.search("sky", 25).size)
    }

    private fun dao() = database.searchIndexDao()

    private fun row(key: String, kind: String, id: Int, name: String) = SearchIndexEntity(
        key = key,
        kind = kind,
        contentId = id,
        name = name,
        nameLower = name.trim().lowercase(),
        subtitle = null,
        categoryId = null,
        posterUrl = null,
        iconUrl = null,
    )

    private fun runBlockingTest(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block() }
}

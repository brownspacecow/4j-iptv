package com.fourj.iptv.testing

import androidx.room.Room
import com.fourj.iptv.data.local.SearchIndexDatabase
import com.fourj.iptv.data.local.VodDatabase
import org.robolectric.RuntimeEnvironment

/**
 * In-memory stand-ins for the app's two databases.
 *
 * Separate from [PanelFixture] because a fair number of tests need a database and no socket - the
 * DAO and migration tests among them - and starting a web server for those would be pure overhead.
 *
 * Room caches the compiled schema between instances, so these are cheap after the first.
 */
fun inMemoryVodDatabase(): VodDatabase = Room.inMemoryDatabaseBuilder(
    RuntimeEnvironment.getApplication(),
    VodDatabase::class.java,
).allowMainThreadQueries().build()

fun inMemorySearchIndexDatabase(): SearchIndexDatabase = Room.inMemoryDatabaseBuilder(
    RuntimeEnvironment.getApplication(),
    SearchIndexDatabase::class.java,
).allowMainThreadQueries().build()

package com.fourj.iptv.di

import android.content.Context
import androidx.room.Room
import com.fourj.iptv.data.local.CredentialStore
import com.fourj.iptv.data.local.FourJDatabase
import com.fourj.iptv.data.local.MIGRATION_1_2
import com.fourj.iptv.data.local.SearchIndexDatabase
import com.fourj.iptv.data.local.VOD_MIGRATION_1_2
import com.fourj.iptv.data.local.VOD_MIGRATION_2_3
import com.fourj.iptv.data.local.VodDatabase
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.data.repository.EpgRepository
import com.fourj.iptv.data.repository.LiveRepository
import com.fourj.iptv.data.repository.SearchRepository
import com.fourj.iptv.data.repository.VodRepository
import com.fourj.iptv.domain.model.ProviderProfile
import okhttp3.OkHttpClient

/**
 * Manual dependency container.
 *
 * Hand-wired rather than Hilt: the graph is small and entirely singleton-scoped, and this keeps a
 * whole annotation processor out of the build. Every dependency is `by lazy`, so nothing is
 * constructed until it is actually needed - opening the database in particular should not be a
 * cost of starting the process.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val credentialStore: CredentialStore by lazy { CredentialStore(appContext) }

    /**
     * Shared with ExoPlayer for media segments. Separate from the API client because panel calls
     * should time out fast while a slow stream should stall rather than error.
     */
    val playerHttpClient: OkHttpClient by lazy { XtreamNetwork.playerClient() }

    private val database: FourJDatabase by lazy {
        Room.databaseBuilder(appContext, FourJDatabase::class.java, DATABASE_NAME)
            // No fallbackToDestructiveMigration: silently wiping a user's cache on a schema change
            // is how apps lose state. Add real migrations and test them instead.
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    private fun apiFor(profile: ProviderProfile) = XtreamNetwork.createApi(profile, debugLogging = false)

    /**
     * Repositories are per profile, because the HTTP client is built around one provider's address
     * and credentials.
     */
    fun liveRepository(profile: ProviderProfile): LiveRepository = LiveRepository(
        profile = profile,
        database = database,
        api = apiFor(profile),
    )

    fun epgRepository(profile: ProviderProfile): EpgRepository = EpgRepository(
        epgDao = database.epgDao(),
        api = apiFor(profile),
    )

    private val vodDatabase: VodDatabase by lazy {
        Room.databaseBuilder(appContext, VodDatabase::class.java, VOD_DATABASE_NAME)
            .addMigrations(VOD_MIGRATION_1_2, VOD_MIGRATION_2_3)
            // Deliberately no fallbackToDestructiveMigration: wiping a viewer's favourites and
            // continue-watching because a schema changed would be a real loss, not a convenience.
            .build()
    }

    fun vodRepository(profile: ProviderProfile): VodRepository = VodRepository(
        profile = profile,
        database = vodDatabase,
        api = XtreamNetwork.createVodApi(profile),
    )

    /**
     * The search index, in a third database file.
     *
     * Separate again from the other two, and this one is genuinely disposable: it holds nothing but
     * copies of names the provider already supplied, so it can be deleted and rebuilt whenever the
     * schema changes instead of carrying a migration. That is the whole reason it is not simply
     * another table in the VOD database, where a schema change would have to be migrated around the
     * viewer's favourites and playback progress.
     */
    private val searchDatabase: SearchIndexDatabase by lazy {
        Room.databaseBuilder(appContext, SearchIndexDatabase::class.java, SEARCH_DATABASE_NAME)
            .build()
    }

    fun searchRepository(profile: ProviderProfile): SearchRepository = SearchRepository(
        profile = profile,
        database = searchDatabase,
        liveApi = apiFor(profile),
        vodApi = XtreamNetwork.createVodApi(profile),
    )

    private companion object {
        const val DATABASE_NAME = "fourj.db"

        /**
         * A separate database file from live TV.
         *
         * The film cache is large and disposable in a way the live cache is not, and keeping them
         * apart means a schema change to one never forces a migration on the other.
         */
        const val VOD_DATABASE_NAME = "fourj-vod.db"

        /** Derived, rebuildable, holds no viewer data. See [searchDatabase]. */
        const val SEARCH_DATABASE_NAME = "fourj-search.db"
    }
}

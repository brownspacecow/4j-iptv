package com.fourj.iptv.data.repository

import com.fourj.iptv.data.local.FourJDatabase
import com.fourj.iptv.data.local.LiveCategoryDao
import com.fourj.iptv.data.local.LiveCategoryEntity
import com.fourj.iptv.data.local.LiveCategorySyncDao
import com.fourj.iptv.data.local.LiveChannelDao
import com.fourj.iptv.data.local.LiveChannelEntity
import com.fourj.iptv.data.local.replaceCategoryChannels
import com.fourj.iptv.data.remote.StreamUrls
import com.fourj.iptv.data.remote.XtreamNetwork
import com.fourj.iptv.data.remote.runCatchingCancellable
import com.fourj.iptv.data.remote.retrying
import com.fourj.iptv.data.remote.xtream.LiveCategoryDto
import com.fourj.iptv.data.remote.xtream.LiveStreamDto
import com.fourj.iptv.data.remote.xtream.XtreamApi
import com.fourj.iptv.domain.model.EpgListing
import com.fourj.iptv.domain.model.LiveCategory
import com.fourj.iptv.domain.model.LiveChannel
import com.fourj.iptv.domain.model.ProviderProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Live TV data for one provider.
 *
 * A repository is created per profile, because the panel address and credentials are baked into
 * the HTTP client. Reads come from the Room cache first so the UI can paint without waiting on the
 * network; the network only fills the cache.
 */
class LiveRepository(
    private val profile: ProviderProfile,
    private val database: FourJDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val api: XtreamApi = XtreamNetwork.createApi(profile, debugLogging = false),
) {

    private val categoryDao: LiveCategoryDao get() = database.liveCategoryDao()
    private val channelDao: LiveChannelDao get() = database.liveChannelDao()
    private val syncDao: LiveCategorySyncDao get() = database.liveCategorySyncDao()

    /**
     * Validate the credentials.
     *
     * A panel answers a bad login with HTTP 200 and a null `user_info`, so a successful HTTP call
     * on its own means nothing - the body has to be checked.
     */
    suspend fun login(): Result<ProviderProfile> = withContext(ioDispatcher) {
        retrying(label = "login") {
            val response = api.login()
            val user = response.userInfo
                ?: error("The provider rejected these credentials.")
            val status = user.status?.lowercase()
            if (status != null && status != "active") {
                error("This account is $status. Check with your provider.")
            }
            profile
        }
    }

    /** The categories list is small, so it is always fetched on connect. */
    suspend fun refreshCategories(): Result<List<LiveCategory>> = withContext(ioDispatcher) {
        retrying(label = "get_live_categories") {
            api.liveCategories().mapNotNull { it.toEntity() }
        }.map { categories ->
            val ordered = categories.sortedBy { it.sortOrder }
            categoryDao.upsertAll(ordered)
            ordered.map { LiveCategory(it.categoryId, it.categoryName) }
        }
    }

    fun observeCategories(): Flow<List<LiveCategory>> =
        categoryDao.observeAll().map { rows -> rows.map { LiveCategory(it.categoryId, it.categoryName) } }

    fun observeChannels(categoryId: String): Flow<List<LiveChannel>> =
        channelDao.observeByCategory(categoryId).map { rows -> rows.map { it.toModel() } }

    fun observeSyncedCategories(): Flow<Set<String>> =
        syncDao.observeAll().map { rows -> rows.map { it.categoryId }.toSet() }

    /**
     * The cached category list, for callers that need it in one shot.
     *
     * A suspend read rather than a collected flow: the search indexer needs the list once, up
     * front, and standing up a Flow subscription to read a value it already has cached would be
     * ceremony. Returns an empty list rather than throwing, because a missing category cache is a
     * normal state on a first run and the caller's fallback is simply "index nothing yet".
     */
    suspend fun cachedCategories(): List<LiveCategory> = withContext(ioDispatcher) {
        runCatching {
            categoryDao.observeAllOnce().map { LiveCategory(it.categoryId, it.categoryName) }
        }.getOrDefault(emptyList())
    }

    /**
     * Fetch a category's channels unless they are already cached.
     *
     * Categories are pulled one at a time on purpose. Asking for the whole live list in a single
     * call returns every channel the provider has, which on a large account is tens of thousands
     * of records and a payload big enough to dominate startup time and memory.
     */
    suspend fun ensureCategoryLoaded(categoryId: String, force: Boolean = false): Result<Unit> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                if (!force && syncDao.isSynced(categoryId)) return@runCatchingCancellable

                val entities = retrying(label = "get_live_streams[$categoryId]") {
                    api.liveStreams(categoryId = categoryId)
                }.getOrThrow().mapIndexedNotNull { index, dto -> dto.toEntity(index) }

                database.replaceCategoryChannels(categoryId, entities, System.currentTimeMillis())
            }
        }

    suspend fun findChannel(streamId: Int): LiveChannel? = withContext(ioDispatcher) {
        channelDao.findById(streamId)?.toModel()
    }

    fun streamUrl(channel: LiveChannel): String = StreamUrls.liveStream(profile, channel)

    fun playlistUrl(): String = StreamUrls.playlist(profile)

    suspend fun clearCache() = withContext(ioDispatcher) {
        channelDao.clear()
        syncDao.clear()
        categoryDao.clear()
    }
}

internal fun LiveCategoryDto.toEntity(): LiveCategoryEntity? {
    val id = categoryId.trim()
    val name = categoryName.trim()
    if (id.isEmpty() || name.isEmpty()) return null
    return LiveCategoryEntity(categoryId = id, categoryName = name, sortOrder = 0)
}

internal fun LiveStreamDto.toEntity(sortOrder: Int): LiveChannelEntity? {
    if (streamId == 0) return null
    val label = name.trim()
    if (label.isEmpty()) return null
    return LiveChannelEntity(
        streamId = streamId,
        name = label,
        categoryId = categoryId?.trim()?.takeIf { it.isNotEmpty() },
        iconUrl = streamIcon?.trim()?.takeIf { it.isNotEmpty() },
        containerExtension = containerExtension?.trim()?.takeIf { it.isNotEmpty() },
        directSource = directSource?.trim()?.takeIf { it.isNotEmpty() },
        httpUserAgent = httpUserAgent?.trim()?.takeIf { it.isNotEmpty() },
        httpReferrer = httpReferrer?.trim()?.takeIf { it.isNotEmpty() },
        hasArchive = (tvArchive ?: 0) == 1,
        archiveDurationDays = tvArchiveDuration,
        sortOrder = sortOrder,
    )
}

internal fun LiveChannelEntity.toModel() = LiveChannel(
    streamId = streamId,
    name = name,
    categoryId = categoryId,
    iconUrl = iconUrl,
    containerExtension = containerExtension,
    directSource = directSource,
    httpUserAgent = httpUserAgent,
    httpReferrer = httpReferrer,
    hasArchive = hasArchive,
    archiveDurationDays = archiveDurationDays,
)


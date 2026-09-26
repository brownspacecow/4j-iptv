package com.fourj.iptv.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * The local channel cache.
 *
 * A large provider can serve tens of thousands of live channels; fetching and rendering that from
 * the network on every launch is what makes most IPTV apps feel slow. Caching it means the browse
 * screens paint immediately and keep working when the provider is briefly unreachable.
 */
@Entity(tableName = "live_categories")
data class LiveCategoryEntity(
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    val sortOrder: Int,
)

/**
 * Records that a category's channels have been fetched and are safe to serve from cache.
 *
 * Without this, every visit to a category would re-request it, because a cache can hold a
 * category's channels while not knowing whether the fetch ever completed. The row is written in
 * the same transaction as the channels, so a failed fetch leaves the category marked uncached.
 */
@Entity(tableName = "live_category_sync")
data class LiveCategorySyncEntity(
    @PrimaryKey val categoryId: String,
    val channelCount: Int,
    val syncedAtMillis: Long,
)

@Entity(tableName = "live_channels")
data class LiveChannelEntity(
    @PrimaryKey val streamId: Int,
    val name: String,
    val categoryId: String?,
    val iconUrl: String?,
    val containerExtension: String?,
    val directSource: String?,
    val httpUserAgent: String?,
    val httpReferrer: String?,
    val hasArchive: Boolean,
    val archiveDurationDays: Int?,
    val sortOrder: Int,
)

@Dao
interface LiveCategoryDao {
    @Query("SELECT * FROM live_categories ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<LiveCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<LiveCategoryEntity>)

    @Query("DELETE FROM live_categories")
    suspend fun clear()
}

@Dao
interface LiveChannelDao {
    @Query("SELECT * FROM live_channels ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<LiveChannelEntity>>

    @Query("SELECT * FROM live_channels WHERE categoryId = :categoryId ORDER BY sortOrder ASC")
    fun observeByCategory(categoryId: String): Flow<List<LiveChannelEntity>>

    @Query("SELECT * FROM live_channels WHERE streamId = :streamId LIMIT 1")
    suspend fun findById(streamId: Int): LiveChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<LiveChannelEntity>)

    @Query("DELETE FROM live_channels WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: String)

    @Query("DELETE FROM live_channels")
    suspend fun clear()
}

@Dao
interface LiveCategorySyncDao {
    @Query("SELECT * FROM live_category_sync")
    fun observeAll(): Flow<List<LiveCategorySyncEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM live_category_sync WHERE categoryId = :categoryId)")
    suspend fun isSynced(categoryId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSynced(state: LiveCategorySyncEntity)

    @Query("DELETE FROM live_category_sync")
    suspend fun clear()
}

@Database(
    entities = [
        LiveCategoryEntity::class,
        LiveChannelEntity::class,
        LiveCategorySyncEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FourJDatabase : RoomDatabase() {
    abstract fun liveCategoryDao(): LiveCategoryDao
    abstract fun liveChannelDao(): LiveChannelDao
    abstract fun liveCategorySyncDao(): LiveCategorySyncDao
}

/**
 * Replace one category's channels and mark it cached, atomically.
 *
 * Room does not allow a `@Transaction` method to reach a second DAO, so this is expressed at the
 * database level instead. It has to be one transaction: a reader must never observe the
 * cleared-but-not-yet-refilled state, which would show up on screen as the list briefly emptying.
 */
suspend fun FourJDatabase.replaceCategoryChannels(
    categoryId: String,
    channels: List<LiveChannelEntity>,
    syncedAtMillis: Long,
) = withTransaction {
    liveChannelDao().clearCategory(categoryId)
    liveChannelDao().upsertAll(channels)
    liveCategorySyncDao().markSynced(
        LiveCategorySyncEntity(categoryId = categoryId, channelCount = channels.size, syncedAtMillis = syncedAtMillis),
    )
}

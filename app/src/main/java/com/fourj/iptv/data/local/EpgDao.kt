package com.fourj.iptv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Cached programme guide for one channel.
 *
 * EPG is expensive to fetch - one request per channel - so it is cached hard and pruned
 * aggressively. Listings that have already finished are of no use to anyone and would otherwise
 * accumulate indefinitely on a device that watches a lot of TV.
 *
 * [streamId] rather than the channel id is used as the key because that is what the panel's
 * `get_short_epg` endpoint is keyed on, and because a channel's guide is useless without the
 * channel itself.
 *
 * The index on [streamId] is declared here, not only in the migration: Room validates a migrated
 * schema against the entity definition, so an index that exists in one place and not the other
 * fails the migration with "Migration didn't properly handle".
 */
@Entity(tableName = "epg_listings", indices = [Index("streamId")])
data class EpgListingEntity(
    /** Panel listing id, unique per channel. */
    @PrimaryKey val listingId: String,
    val streamId: Int,
    val title: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val description: String?,
    val channelId: String?,
    val nowPlaying: Boolean,
)

@Dao
interface EpgDao {

    @Query("SELECT * FROM epg_listings WHERE streamId = :streamId ORDER BY startEpochSeconds ASC")
    fun observeForStream(streamId: Int): Flow<List<EpgListingEntity>>

    @Query("SELECT * FROM epg_listings WHERE streamId = :streamId ORDER BY startEpochSeconds ASC")
    suspend fun forStream(streamId: Int): List<EpgListingEntity>

    /**
     * Every cached listing, so the UI can derive what is on now across all channels at once.
     *
     * Querying "what is on now" in SQL would mean re-running the query every time the clock moved,
     * since the parameter changes. The cache is small by construction - finished listings are
     * pruned - so it is cheaper to observe it whole and pick out the current programme in memory.
     */
    @Query("SELECT * FROM epg_listings ORDER BY startEpochSeconds ASC")
    fun observeAll(): Flow<List<EpgListingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(listings: List<EpgListingEntity>)

    @Query("DELETE FROM epg_listings WHERE streamId = :streamId")
    suspend fun clearStream(streamId: Int)

    /**
     * Drop anything that finished more than [graceSeconds] ago.
     *
     * A grace period rather than deleting on a hard boundary, so a programme that is *just*
     * started is never thrown away by a prune racing the fetch.
     */
    @Query("DELETE FROM epg_listings WHERE endEpochSeconds < :cutoffEpochSeconds")
    suspend fun pruneFinished(cutoffEpochSeconds: Long)

    @Query("SELECT COUNT(*) FROM epg_listings")
    suspend fun count(): Int
}

package com.fourj.iptv.data.repository

import android.util.Log
import com.fourj.iptv.data.local.EpgDao
import com.fourj.iptv.data.local.EpgListingEntity
import com.fourj.iptv.data.remote.runCatchingCancellable
import com.fourj.iptv.data.remote.retrying
import com.fourj.iptv.data.remote.xtream.EpgListingDto
import com.fourj.iptv.data.remote.xtream.ShortEpgResponse
import com.fourj.iptv.data.remote.xtream.XtreamApi
import com.fourj.iptv.domain.model.EpgListing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Programme guide, cached hard and fetched sparingly.
 *
 * **Why this is not done naively.** The panel exposes EPG one channel at a time - there is no
 * cheap "give me everything" call. A category on a large provider holds hundreds of channels, so
 * asking for a guide for each one on the way past would be hundreds of requests every time the
 * user opened the list. Providers rate-limit, and a client that hammers them stops working for
 * everyone behind the same account.
 *
 * The policy instead:
 *  - only the channels actually on screen are considered ([VISIBLE_CHANNEL_WINDOW])
 *  - at most [MAX_PARALLEL_REQUESTS] requests are in flight at once
 *  - a cached listing is reused until it is nearly over, so re-opening a list costs nothing
 *  - anything already finished is pruned, so the cache cannot grow without bound
 */
class EpgRepository(
    private val epgDao: EpgDao,
    private val api: XtreamApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Cached-first: emits whatever is stored immediately, then updates when a fetch lands.
     *
     * `flowOn(Dispatchers.IO)` is applied here rather than left to the caller. Room rejects
     * queries issued on the main dispatcher, and a flow that is safe only if every caller
     * remembers to shift it is a trap - one that has already been sprung once.
     */
    fun observeForStream(streamId: Int): Flow<List<EpgListing>> =
        epgDao.observeForStream(streamId)
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    /** Every cached listing, for deriving what is on now across all channels. */
    fun observeAll(): Flow<List<EpgListing>> =
        epgDao.observeAll()
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    /**
     * Ensure a guide exists for [streamIds], fetching only what is missing or nearly finished.
     *
     * Safe to call on every appearance of a list. Already-cached channels cost nothing, and a
     * provider that has no guide for a channel is asked once and then left alone until the
     * cache is cleared.
     */
    suspend fun ensureGuideFor(streamIds: List<Int>) = withContext(ioDispatcher) {
        val window = streamIds.take(VISIBLE_CHANNEL_WINDOW)
        val nowSeconds = now() / 1000

        val stale = window.filter { streamId ->
            val cached = epgDao.forStream(streamId)
            // Nothing cached, or the only usable listing is about to end.
            cached.none { it.endEpochSeconds > nowSeconds + STALE_MARGIN_SECONDS }
        }
        if (stale.isEmpty()) return@withContext

        Log.i(TAG, "fetching guide for ${stale.size} of ${window.size} visible channels")
        val gate = Semaphore(MAX_PARALLEL_REQUESTS)
        coroutineScope {
            stale.map { streamId ->
                async {
                    gate.withPermit { fetchAndCache(streamId) }
                }
            }.awaitAll()
        }
        epgDao.pruneFinished(nowSeconds - PRUNE_GRACE_SECONDS)
    }

    /**
     * Force a refresh for the channel being watched.
     *
     * The channel in front of the viewer is the one guide worth being current on, so this skips
     * the cache and the staleness check entirely.
     */
    suspend fun refreshNow(streamId: Int) = withContext(ioDispatcher) {
        fetchAndCache(streamId)
        epgDao.pruneFinished(now() / 1000 - PRUNE_GRACE_SECONDS)
    }

    private suspend fun fetchAndCache(streamId: Int) {
        val now = now() / 1000
        val short = fetch(streamId) { api.shortEpg(streamId = streamId, limit = EPG_LIMIT) }

        // A panel was observed returning only listings that had already ended from
        // get_short_epg, so nothing matched the clock and the guide looked simply absent. Fall
        // back to the full-day endpoint before concluding the channel has no guide at all.
        val listings = if (short.none { it.overlaps(now) }) {
            val full = fetch(streamId) { api.simpleDataTable(streamId = streamId) }
            if (full.any { it.overlaps(now) }) full else short
        } else {
            short
        }

        if (listings.isEmpty()) {
            Log.i(TAG, "stream $streamId: provider returned no listings")
            return
        }

        val entities = listings.mapNotNull { it.toEntity(streamId) }
        val onAir = entities.count { it.startEpochSeconds <= now && it.endEpochSeconds > now }
        Log.i(
            TAG,
            "stream $streamId: ${listings.size} listings, $onAir on air now; " +
                "stored as: " + entities.take(2).joinToString(" | ") { "'${it.title}'" },
        )
        epgDao.upsertAll(entities)
    }

    private suspend fun fetch(
        streamId: Int,
        call: suspend () -> ShortEpgResponse,
    ): List<EpgListingDto> = retrying(label = "epg[$streamId]", block = { call().listings })
        .getOrElse { throwable ->
            // A channel with no guide is normal, not an error worth surfacing. Log it and move on
            // so one dead channel cannot fail the whole batch.
            Log.w(TAG, "no guide for stream $streamId: ${throwable.message}")
            emptyList()
        }

    suspend fun clear() = withContext(ioDispatcher) { epgDao.clearStream(-1) }

    private companion object {
        const val TAG = "4J"

        /** Only the top of the list is ever fetched. A television shows a handful of rows. */
        const val VISIBLE_CHANNEL_WINDOW = 14

        /** Enough to keep a panel from treating the client as a flood. */
        const val MAX_PARALLEL_REQUESTS = 4

        /** A listing with less than this left is treated as already fetched. */
        const val STALE_MARGIN_SECONDS = 5 * 60L

        /** Keep a little history so a programme that has just started is not pruned. */
        const val PRUNE_GRACE_SECONDS = 60 * 60L

        /** Enough for now/next plus a little slack. */
        const val EPG_LIMIT = 4
    }
}

internal fun EpgListingDto.overlaps(nowSeconds: Long): Boolean =
    (startTimestamp ?: 0L) <= nowSeconds && (stopTimestamp ?: 0L) > nowSeconds

internal fun EpgListingDto.toEntity(streamId: Int): EpgListingEntity? {
    if (title.isBlank()) return null
    val id = id.ifBlank { "${streamId}_${start}_${end}" }
    return EpgListingEntity(
        listingId = id,
        streamId = streamId,
        // Some panels Base64-encode titles; see decodePanelText for why this is not done blindly.
        title = decodePanelText(title),
        startEpochSeconds = startTimestamp ?: 0L,
        endEpochSeconds = stopTimestamp ?: 0L,
        description = description?.takeIf { it.isNotBlank() }?.let(::decodePanelText),
        channelId = channelId,
        nowPlaying = nowPlaying == 1,
    )
}

internal fun EpgListingEntity.toModel() = EpgListing(
    id = listingId,
    streamId = streamId,
    title = title,
    start = startEpochSeconds,
    end = endEpochSeconds,
    description = description,
    channelId = channelId,
    nowPlaying = nowPlaying,
)


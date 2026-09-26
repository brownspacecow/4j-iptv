package com.fourj.iptv.domain.model

/**
 * A provider the user has configured: where the Xtream Codes API lives, and how to authenticate.
 *
 * [baseUrl] is always normalised to `scheme://host[:port]` with no trailing slash and no path.
 */
data class ProviderProfile(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    /**
     * True when the profile is plain HTTP. Worth surfacing: over HTTP anyone able to observe the
     * network path can read these credentials and the viewing traffic.
     */
    val usesCleartext: Boolean
        get() = baseUrl.startsWith("http://", ignoreCase = true)
}

data class LiveCategory(
    val id: String,
    val name: String,
)

data class LiveChannel(
    val streamId: Int,
    val name: String,
    val categoryId: String?,
    val iconUrl: String?,
    /** `ts` or `m3u8` - Xtream panels use this to decide the container it serves. */
    val containerExtension: String?,
    /** Some panels hand back a ready-made URL instead of the usual `live/...` pattern. */
    val directSource: String?,
    /** Many panels require these headers; without them a noticeable share of channels return 403. */
    val httpUserAgent: String?,
    val httpReferrer: String?,
    val hasArchive: Boolean,
    val archiveDurationDays: Int?,
)

/** A now/next listing for a channel's guide. */
data class EpgListing(
    val id: String,
    val title: String,
    val start: Long,
    val end: Long,
    val description: String?,
    val channelId: String?,
    val nowPlaying: Boolean,
)

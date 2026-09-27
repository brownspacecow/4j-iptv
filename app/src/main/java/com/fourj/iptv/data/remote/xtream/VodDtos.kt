package com.fourj.iptv.data.remote.xtream

import kotlinx.serialization.SerialName
import com.fourj.iptv.data.remote.XtreamNetwork
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * VOD and series payloads.
 *
 * Same philosophy as the live DTOs: every field optional with a default, because panels vary
 * wildly in what they populate. A series with no cast, or a film with no rating, is normal and
 * must not fail the whole listing.
 */

@Serializable
data class VodCategoryDto(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("parent_id") val parentId: Int? = null,
)

@Serializable
data class VodStreamDto(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("stream_id") val streamId: Int = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("rating_5based") val rating5: Double? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("direct_source") val directSource: String? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("http_user_agent") val httpUserAgent: String? = null,
    @SerialName("http_referrer") val httpReferrer: String? = null,
    // Present on some panels for on-demand items rather than live.
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("backdrop_path") val backdropPath: List<String>? = null,
)

@Serializable
data class SeriesCategoryDto(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("parent_id") val parentId: Int? = null,
)

@Serializable
data class SeriesDto(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("series_id") val seriesId: Int = 0,
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("cast") val cast: String? = null,
    @SerialName("director") val director: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("last_modified") val lastModified: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("rating_5based") val rating5: Double? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("backdrop_path") val backdropPath: List<String>? = null,
)

/**
 * `action=get_series_info`.
 *
 * Three layouts are now known to exist, and reading only the documented one makes every series on
 * the other two report zero episodes - which looks exactly like empty data and sends the diagnosis
 * somewhere unhelpful. Use [seasons], which normalises all three.
 *
 *  - `{"info": {...}, "episodes": {"season": [ {episodes: [...]}, ... ]}}` - the common Xtream form
 *  - `{"seasons": [ ... ], "episodes": { "4": [ {...} ], "13": [ {...} ] }}` - seasons keyed by
 *    number, seen on a panel proxying TMDB metadata
 *  - `{"seasons": [ ... ]}` - season summaries with no episodes at all
 *
 * Kept as raw JSON for [episodes] on purpose: a typed model silently discards whatever it does not
 * recognize, and that is precisely what made this hard to see.
 */
@Serializable
data class SeriesInfoResponse(
    @SerialName("info") val info: SeriesInfoDto? = null,
    @SerialName("episodes") val episodes: JsonElement? = null,
    @SerialName("seasons") val topLevelSeasons: List<SeasonDto>? = null,
    // Some panels put the series metadata alongside the seasons rather than under `info`.
    @SerialName("series") val series: SeriesInfoDto? = null,
) {
    val metadata: SeriesInfoDto?
        get() = info ?: series

    /**
     * Whichever layout this panel used, normalised to a list of seasons.
     *
     * Prefers layouts that carry actual episodes, and only falls back to the season summaries when
     * nothing better is on offer - so a series with real episodes is never reported as empty just
     * because a summary list happened to be present too.
     */
    val seasons: List<SeasonDto>
        get() {
            val fromEpisodes = nestedSeasons().ifEmpty { episodesKeyedBySeason() }
            if (fromEpisodes.isNotEmpty()) return fromEpisodes
            // Seasons at the top level. Kept as they arrive, including any episodes hanging off
            // them - a season that carries its own episodes is a layout seen in the wild, and
            // blanking that array here would throw away the only copy of the data.
            return topLevelSeasons.orEmpty()
        }

    /** The documented form: `episodes.season` is a list of seasons, each with its own episodes. */
    private fun nestedSeasons(): List<SeasonDto> {
        val holder = episodes as? JsonObject ?: return emptyList()
        val list = holder["season"] as? JsonArray ?: return emptyList()
        return XtreamNetwork.json.decodeFromJsonElement(ListSerializer(SeasonDto.serializer()), list)
    }

    /**
     * The map form: `{"4": [episode, ...], "13": [episode, ...]}`, keyed by season number.
     *
     * The season number comes from the key rather than from each episode, because panels that send
     * this shape have been seen leaving it out of the episode objects.
     */
    private fun episodesKeyedBySeason(): List<SeasonDto> {
        val holder = episodes as? JsonObject ?: return emptyList()
        if (holder.containsKey("season")) return emptyList()
        val episodeList = ListSerializer(EpisodeDto.serializer())

        return holder.entries.mapNotNull { (seasonKey, value) ->
            val list = value as? JsonArray ?: return@mapNotNull null
            if (list.isEmpty()) return@mapNotNull null
            val number = seasonKey.toIntOrNull() ?: return@mapNotNull null
            val decoded = runCatching {
                XtreamNetwork.json.decodeFromJsonElement(episodeList, list)
            }.getOrDefault(emptyList())
            SeasonDto(
                id = seasonKey,
                name = "Season $number",
                // Fill the season in on each episode; panels sending this shape often omit it.
                episodes = decoded.map { if (it.season == null) it.copy(season = number) else it },
            )
        }.sortedBy { it.id?.toIntOrNull() ?: Int.MAX_VALUE }
    }
}

@Serializable
data class SeriesInfoDto(
    @SerialName("name") val name: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("cast") val cast: String? = null,
    @SerialName("director") val director: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("backdrop_path") val backdropPath: List<String>? = null,
)

@Serializable
data class EpisodesDto(
    @SerialName("season") val seasons: List<SeasonDto> = emptyList(),
)

@Serializable
data class SeasonDto(
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("episodes") val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    @SerialName("id") val id: String = "",
    /**
     * Raw, because panels disagree on the type: the common form sends a number, and one sending
     * TMDB-shaped metadata sent `"1"` as a string. Decoding a string into an Int throws, and one
     * bad episode would then take the whole series with it - so the raw value is kept and parsed
     * leniently in [episodeNum].
     */
    @SerialName("episode_num") val episodeNumRaw: JsonElement? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("streams") val streams: EpisodeStreamsDto? = null,
    /** The TMDB-shaped form carries a stream here instead of under `streams`. */
    @SerialName("direct_source") val directSource: String? = null,
) {
    val episodeNum: Int?
        get() = (episodeNumRaw as? JsonPrimitive)
            ?.content
            ?.trim()
            ?.toDoubleOrNull()
            ?.toInt()
}

@Serializable
data class EpisodeStreamsDto(
    @SerialName("direct") val direct: DirectStreamDto? = null,
)

@Serializable
data class DirectStreamDto(
    @SerialName("source") val source: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

package com.fourj.iptv.data.remote.xtream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * The nesting is irregular: seasons live under `episodes.season`, each with its own `episodes`
 * array, and the playable URL is buried under `streams.direct.source` alongside a MIME type.
 */
@Serializable
data class SeriesInfoResponse(
    @SerialName("info") val info: SeriesInfoDto? = null,
    @SerialName("episodes") val episodes: EpisodesDto? = null,
)

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
    @SerialName("episode_num") val episodeNum: Int? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("streams") val streams: EpisodeStreamsDto? = null,
)

@Serializable
data class EpisodeStreamsDto(
    @SerialName("direct") val direct: DirectStreamDto? = null,
)

@Serializable
data class DirectStreamDto(
    @SerialName("source") val source: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

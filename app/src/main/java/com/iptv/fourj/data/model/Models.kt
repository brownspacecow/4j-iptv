package com.iptv.fourj.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Provider(
    val id: Long = 0,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val isActive: Boolean = true
)

@Serializable
data class UserInfo(
    val username: String = "",
    val password: String = "",
    val message: String = "",
    val auth: Int = 0,
    val status: String = "",
    @SerialName("exp_date") val expDate: String = "",
    @SerialName("is_trial") val isTrial: String = "",
    @SerialName("active_cons") val activeCons: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("max_connections") val maxConnections: String = ""
)

@Serializable
data class ServerInfo(
    val url: String = "",
    val port: String = "",
    val httpsPort: String = "",
    val serverProtocol: String = "",
    val rtmpPort: String = "",
    val timezone: String = "",
    val timeNow: String = "",
    val version: String = "",
    val xui: Boolean = false
)

@Serializable
data class LoginResponse(
    @SerialName("user_info") val userInfo: UserInfo = UserInfo(),
    @SerialName("server_info") val serverInfo: ServerInfo = ServerInfo()
)

@Serializable
data class LiveCategory(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("parent_id") val parentId: String = ""
)

@Serializable
data class LiveStream(
    val num: String = "",
    val name: String,
    @SerialName("stream_type") val streamType: String = "",
    @SerialName("stream_id") val streamId: String,
    @SerialName("stream_icon") val streamIcon: String = "",
    @SerialName("epg_channel_id") val epgChannelId: String = "",
    val added: String = "",
    @SerialName("category_id") val categoryId: String,
    @SerialName("custom_sid") val customSid: String = "",
    @SerialName("tv_archive") val tvArchive: String = "",
    @SerialName("direct_source") val directSource: String = "",
    val rating: String = ""
)

@Serializable
data class VodCategory(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("parent_id") val parentId: String = ""
)

@Serializable
data class VodStream(
    val num: String = "",
    val name: String,
    @SerialName("stream_type") val streamType: String = "",
    @SerialName("stream_id") val streamId: String,
    @SerialName("stream_icon") val streamIcon: String = "",
    val rating: String = "",
    @SerialName("rating5based") val rating5based: String = "",
    val added: String = "",
    @SerialName("category_id") val categoryId: String,
    @SerialName("container_extension") val containerExtension: String = "",
    @SerialName("custom_sid") val customSid: String = "",
    @SerialName("direct_source") val directSource: String = ""
)

@Serializable
data class SeriesCategory(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("parent_id") val parentId: String = ""
)

@Serializable
data class SeriesStream(
    val num: String = "",
    val name: String,
    @SerialName("series_id") val seriesId: String,
    val cover: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    @SerialName("releaseDate") val releaseDate: String = "",
    @SerialName("last_modified") val lastModified: String = "",
    val rating: String = "",
    @SerialName("rating5based") val rating5based: String = "",
    @SerialName("category_id") val categoryId: String = ""
)

@Serializable
data class SeriesInfo(
    val seasons: List<Season> = emptyList(),
    val info: SeriesDetail = SeriesDetail(),
    val episodes: Map<String, List<Episode>> = emptyMap()
)

@Serializable
data class SeriesDetail(
    val name: String = "",
    val cover: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    @SerialName("releaseDate") val releaseDate: String = "",
    val rating: String = "",
    @SerialName("rating5based") val rating5based: String = ""
)

@Serializable
data class Season(
    @SerialName("air_date") val airDate: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    val cover: String = ""
)

@Serializable
data class Episode(
    val id: String = "",
    @SerialName("episode_num") val episodeNum: String = "",
    val title: String = "",
    @SerialName("container_extension") val containerExtension: String = "",
    val info: EpisodeInfo = EpisodeInfo()
)

@Serializable
data class EpisodeInfo(
    @SerialName("movie_image") val movieImage: String = "",
    @SerialName("movie_image_2") val movieImage2: String = "",
    @SerialName("movie_image_3") val movieImage3: String = "",
    @SerialName("movie_image_4") val movieImage4: String = "",
    @SerialName("movie_image_5") val movieImage5: String = "",
    val plot: String = "",
    @SerialName("releasedate") val releasedate: String = "",
    val rating: String = ""
)

@Serializable
data class EpgListing(
    val id: String = "",
    @SerialName("epg_id") val epgId: String = "",
    val title: String = "",
    val lang: String = "",
    val start: String = "",
    val end: String = "",
    val description: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("start_timestamp") val startTimestamp: String = "",
    @SerialName("stop_timestamp") val stopTimestamp: String = ""
)

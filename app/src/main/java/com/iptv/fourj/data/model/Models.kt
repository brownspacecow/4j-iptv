package com.iptv.fourj.data.model

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
    val expDate: String = "",
    val isTrial: String = "",
    val activeConnections: String = "",
    val createdAt: String = "",
    val maxConnections: String = ""
)

@Serializable
data class ServerInfo(
    val url: String = "",
    val port: String = "",
    val httpsPort: String = "",
    val serverProtocol: String = "",
    val rtmpPort: String = "",
    val timezone: String = "",
    val timeNow: String = ""
)

@Serializable
data class LoginResponse(
    val userInfo: UserInfo = UserInfo(),
    val serverInfo: ServerInfo = ServerInfo()
)

@Serializable
data class LiveCategory(
    val categoryId: String,
    val categoryName: String,
    val parentId: String = ""
)

@Serializable
data class LiveStream(
    val num: String = "",
    val name: String,
    val streamType: String = "",
    val streamId: String,
    val streamIcon: String = "",
    val epgChannelId: String = "",
    val added: String = "",
    val categoryId: String,
    val customSid: String = "",
    val tvArchive: String = "",
    val directSource: String = "",
    val rating: String = ""
)

@Serializable
data class VodCategory(
    val categoryId: String,
    val categoryName: String,
    val parentId: String = ""
)

@Serializable
data class VodStream(
    val num: String = "",
    val name: String,
    val streamType: String = "",
    val streamId: String,
    val streamIcon: String = "",
    val rating: String = "",
    val rating5based: String = "",
    val added: String = "",
    val categoryId: String,
    val containerExtension: String = "",
    val customSid: String = "",
    val directSource: String = ""
)

@Serializable
data class SeriesCategory(
    val categoryId: String,
    val categoryName: String,
    val parentId: String = ""
)

@Serializable
data class SeriesStream(
    val num: String = "",
    val name: String,
    val seriesId: String,
    val cover: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val lastModified: String = "",
    val rating: String = "",
    val rating5based: String = "",
    val categoryId: String = ""
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
    val releaseDate: String = "",
    val rating: String = "",
    val rating5based: String = ""
)

@Serializable
data class Season(
    val airDate: String = "",
    val episodeCount: Int = 0,
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    val seasonNumber: Int = 0,
    val cover: String = ""
)

@Serializable
data class Episode(
    val id: String = "",
    val episodeNum: String = "",
    val title: String = "",
    val containerExtension: String = "",
    val info: EpisodeInfo = EpisodeInfo()
)

@Serializable
data class EpisodeInfo(
    val movieImage: String = "",
    val movieImage2: String = "",
    val movieImage3: String = "",
    val movieImage4: String = "",
    val movieImage5: String = "",
    val plot: String = "",
    val releasedate: String = "",
    val rating: String = ""
)

@Serializable
data class EpgListing(
    val id: String = "",
    val epgId: String = "",
    val title: String = "",
    val lang: String = "",
    val start: String = "",
    val end: String = "",
    val description: String = "",
    val channelId: String = "",
    val startTimestamp: String = "",
    val stopTimestamp: String = ""
)

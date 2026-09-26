package com.fourj.iptv.data.remote.xtream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Xtream Codes / panel API payloads.
 *
 * Every field is nullable with a default. Panels are not consistent: the same provider can omit
 * fields, return them as strings where another returns numbers, and return `null` for anything
 * the account is not entitled to. Decoding is therefore lenient, and anything essential is
 * checked after the fact rather than being allowed to throw during deserialisation.
 */

@Serializable
data class LoginResponse(
    @SerialName("user_info") val userInfo: UserInfoDto? = null,
    @SerialName("server_info") val serverInfo: ServerInfoDto? = null,
    @SerialName("auth") val auth: Int = 0,
)

@Serializable
data class UserInfoDto(
    @SerialName("username") val username: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("is_trial") val isTrial: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
)

@Serializable
data class ServerInfoDto(
    @SerialName("url") val url: String? = null,
    @SerialName("port") val port: String? = null,
    @SerialName("https_port") val httpsPort: String? = null,
    @SerialName("server_protocol") val serverProtocol: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("timestamp_now") val timestampNow: Long? = null,
    @SerialName("time_now") val timeNow: String? = null,
)

@Serializable
data class LiveCategoryDto(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("parent_id") val parentId: Int? = null,
)

@Serializable
data class LiveStreamDto(
    @SerialName("name") val name: String = "",
    @SerialName("stream_id") val streamId: Int = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("num") val num: Int? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("direct_source") val directSource: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("http_user_agent") val httpUserAgent: String? = null,
    @SerialName("http_referrer") val httpReferrer: String? = null,
    @SerialName("tv_archive") val tvArchive: Int? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: Int? = null,
)

@Serializable
data class ShortEpgResponse(
    @SerialName("epg_listings") val listings: List<EpgListingDto> = emptyList(),
)

@Serializable
data class EpgListingDto(
    @SerialName("id") val id: String = "",
    @SerialName("epg_id") val epgId: String? = null,
    @SerialName("title") val title: String = "",
    @SerialName("lang") val lang: String? = null,
    @SerialName("start") val start: String = "",
    @SerialName("end") val end: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("start_timestamp") val startTimestamp: Long? = null,
    @SerialName("stop_timestamp") val stopTimestamp: Long? = null,
    @SerialName("now_playing") val nowPlaying: Int? = null,
)

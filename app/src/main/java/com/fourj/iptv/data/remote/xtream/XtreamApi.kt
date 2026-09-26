package com.fourj.iptv.data.remote.xtream

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The subset of the Xtream Codes panel API needed for live TV.
 *
 * Credentials are not declared per-method: [XtreamAuthInterceptor] appends `username` and
 * `password` to every request, because the panel expects them on all of them.
 */
interface XtreamApi {

    /**
     * `action=login`. Validates the credentials and returns account plus server details.
     *
     * A panel signals bad credentials with HTTP 200 and a `user_info` of null, so a 200 alone
     * does not mean the login worked.
     */
    @GET("player_api.php")
    suspend fun login(
        @Query("action") action: String = "login",
    ): LoginResponse

    @GET("player_api.php")
    suspend fun liveCategories(
        @Query("action") action: String = "get_live_categories",
    ): List<LiveCategoryDto>

    /**
     * Live streams, optionally narrowed to one category. Omitting [categoryId] returns the whole
     * live list, which on a large provider is a very large payload - prefer a category.
     */
    @GET("player_api.php")
    suspend fun liveStreams(
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String? = null,
    ): List<LiveStreamDto>

    @GET("player_api.php")
    suspend fun shortEpg(
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: Int,
        @Query("limit") limit: Int = 4,
    ): ShortEpgResponse
}

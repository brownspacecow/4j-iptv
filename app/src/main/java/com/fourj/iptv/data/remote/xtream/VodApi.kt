package com.fourj.iptv.data.remote.xtream

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * VOD and series, the on-demand half of the Xtream Codes API.
 *
 * Same shape as [XtreamApi]: credentials are appended by [XtreamAuthInterceptor], so only the
 * action and its own parameters appear here.
 *
 * Categories are fetched one at a time, for the same reason live channels are: a provider's film
 * library is easily six figures, and asking for all of it in one call produces a response so large
 * that panels truncate it.
 */
interface VodApi {

    @GET("player_api.php")
    suspend fun vodCategories(
        @Query("action") action: String = "get_vod_categories",
    ): List<VodCategoryDto>

    @GET("player_api.php")
    suspend fun vodStreams(
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("start") start: Int? = null,
    ): List<VodStreamDto>

    @GET("player_api.php")
    suspend fun seriesCategories(
        @Query("action") action: String = "get_series_categories",
    ): List<SeriesCategoryDto>

    /**
     * Series, optionally narrowed to one category and optionally paged.
     *
     * [limit] and [start] exist for the search indexer. This provider's catalogue is on the order
     * of a hundred thousand titles, and asking for all of it in one call is what truncated a
     * response and killed the app during testing - so the indexer walks the catalogue a page at a
     * time instead. Paging by [categoryId] is the outer loop and paging within a category the inner
     * one, because a category is the unit the panel already handles well.
     */
    @GET("player_api.php")
    suspend fun series(
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("start") start: Int? = null,
    ): List<SeriesDto>

    /**
     * The two catalogue reads, as raw bodies.
     *
     * Same reason as [XtreamApi.liveStreamsRaw]: a large shelf is truncated by this provider, and
     * the JSON converter turns that into a failure that throws away every film or series that did
     * arrive. Reading the body lets the truncated array be closed at its last complete title.
     */
    @GET("player_api.php")
    suspend fun vodStreamsRaw(
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("start") start: Int? = null,
    ): ResponseBody

    @GET("player_api.php")
    suspend fun seriesRaw(
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("start") start: Int? = null,
    ): ResponseBody

    /**
     * `action=get_series_info`, returned as raw JSON rather than a typed model.
     *
     * Deliberate. The payload shape varies between panels - this one nests seasons at the top
     * level rather than under `episodes`, and where the episodes sit inside a season varies too -
     * and a typed model silently drops whatever it does not recognise, which looks exactly like a
     * series with no episodes. Decoding here means the keys can be logged when nothing is found,
     * instead of the failure being invisible.
     */
    @GET("player_api.php")
    suspend fun seriesInfo(
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: Int,
    ): JsonObject
}

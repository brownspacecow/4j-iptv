package com.fourj.iptv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VodDao {

    @Query("SELECT * FROM vod_categories WHERE kind = :kind ORDER BY sortOrder ASC")
    fun observeVodCategories(kind: String): Flow<List<VodCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVodCategories(rows: List<VodCategoryEntity>)

    @Query("SELECT * FROM movies WHERE categoryId = :categoryId ORDER BY sortOrder ASC")
    fun observeMovies(categoryId: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies ORDER BY sortOrder ASC")
    fun observeAllMovies(): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE movieId = :movieId LIMIT 1")
    suspend fun findMovie(movieId: Int): MovieEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovies(rows: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE categoryId = :categoryId")
    suspend fun clearMoviesInCategory(categoryId: String)

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY sortOrder ASC")
    fun observeSeries(categoryId: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE seriesId = :seriesId LIMIT 1")
    suspend fun findSeries(seriesId: Int): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(rows: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE categoryId = :categoryId")
    suspend fun clearSeriesInCategory(categoryId: String)

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND seasonNumber = :seasonNumber ORDER BY episodeNumber ASC")
    fun observeEpisodesInSeason(seriesId: Int, seasonNumber: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId")
    suspend fun episodesFor(seriesId: Int): List<EpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(rows: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    suspend fun clearEpisodes(seriesId: Int)
}

@Dao
interface LibraryDao {

    @Query("SELECT * FROM playback_progress ORDER BY updatedAtMillis DESC")
    fun observeAllProgress(): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM playback_progress WHERE contentKey = :contentKey LIMIT 1")
    suspend fun progressFor(contentKey: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(row: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE contentKey = :contentKey")
    suspend fun clearProgress(contentKey: String)

    @Query("DELETE FROM playback_progress")
    suspend fun clearAllProgress()

    @Query("SELECT * FROM favourites ORDER BY addedAtMillis DESC")
    fun observeFavourites(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites WHERE contentKey = :contentKey LIMIT 1")
    suspend fun favouriteFor(contentKey: String): FavouriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavourite(row: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE contentKey = :contentKey")
    suspend fun removeFavourite(contentKey: String)

    @Query("DELETE FROM favourites")
    suspend fun clearFavourites()
}

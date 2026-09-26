package com.fourj.iptv.domain.model

/** A category of on-demand films. */
data class VodCategory(
    val id: String,
    val name: String,
)

/** An on-demand film. */
data class Movie(
    val id: Int,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val containerExtension: String?,
    val directSource: String?,
    val httpUserAgent: String?,
    val httpReferrer: String?,
    val rating: Double?,
    val plot: String?,
    val durationSeconds: Long?,
)

/** A series, before its seasons and episodes are loaded. */
data class Series(
    val id: Int,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
)

/** One season of a series. */
data class Season(
    val id: String,
    val number: Int,
    val name: String,
    val posterUrl: String?,
)

/** One episode, with enough detail to play it and to resume into it. */
data class Episode(
    val id: String,
    val seriesId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String?,
    val sourceUrl: String?,
    val mimeType: String?,
    /** Seconds. Xtream reports this per episode in `episode_run_time`, often as "00:42:00". */
    val durationSeconds: Long?,
)

/**
 * Where a viewer left off.
 *
 * Keyed by content id so the same field serves films and episodes, and so "continue watching" is
 * one query rather than two.
 */
data class PlaybackProgress(
    val contentId: Int,
    val kind: ContentKind,
    val title: String,
    val subtitle: String?,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val posterUrl: String?,
    val updatedAtMillis: Long,
) {
    /** Fraction watched, 0f..1f. Treated as complete only once genuinely near the end. */
    val fraction: Float
        get() = if (durationSeconds <= 0) 0f
        else (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)

    val isResumable: Boolean
        get() = durationSeconds > 0 && fraction in RESUME_FLOOR..COMPLETION_THRESHOLD

    companion object {
        /** Below this the viewer barely started; showing it in "continue watching" is noise. */
        const val RESUME_FLOOR = 0.01f

        /** Above this it is finished, and belongs in history rather than "continue". */
        const val COMPLETION_THRESHOLD = 0.95f
    }
}

/**
 * What a favourite or a resume position refers to.
 *
 * Four kinds rather than two: the key has to distinguish a live channel from a film and an episode,
 * because all of them use bare numeric ids drawn from the same panel and they overlap freely.
 */
enum class ContentKind { LIVE_CHANNEL, MOVIE, SERIES, EPISODE }

/** A favourite, live or on-demand. */
data class Favourite(
    /** Stable key: live channels and movies share the numeric id space across panels, so the kind
     *  is part of the key to keep a live channel from colliding with a film of the same id. */
    val contentKey: String,
    val kind: ContentKind,
    val contentId: Int,
    val name: String,
    val subtitle: String?,
    val posterUrl: String?,
    val addedAtMillis: Long,
)

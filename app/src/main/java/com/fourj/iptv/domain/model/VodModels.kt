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

/**
 * A cached on-demand shelf and the namespace it belongs to.
 *
 * Separate from [VodCategory] because the browse screens only ever hold one kind at a time, while
 * the search indexer needs both and has to know which is which. Carrying the kind beats
 * re-deriving it from the id or the name - see
 * [com.fourj.iptv.data.repository.VodRepository.cachedShelves].
 */
data class VodShelf(
    val kind: ContentKind,
    val category: VodCategory,
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
    /**
     * The panel's own identifier for this episode, used to build the stream URL.
     *
     * Distinct from [id], which is this app's row key. Kept separately because a panel that
     * leaves `direct_source` empty still serves the episode from `/series/{user}/{pass}/{id}.{ext}`
     * - and the row key is namespaced by series, so it cannot be used to build that path.
     */
    val streamId: String? = null,
) {
    /**
     * Whether a stream URL can be built for this episode.
     *
     * Mirrors the rule in the URL builder: either the panel supplied a URL, or there is enough to
     * construct the conventional `/series/` path. Kept here so the list can grey out an unplayable
     * episode before it is pressed, rather than only explaining afterwards.
     */
    val isPlayable: Boolean
        get() = !sourceUrl.isNullOrBlank() || !streamId.isNullOrBlank()
}

/**
 * Where a viewer left off.
 *
 * Identified by [contentKey] rather than by a numeric id. Live channels and films have one, but an
 * episode's identifier in the panel is an arbitrary string ("3001-s1-e1", or an info hash), and
 * hashing it to squeeze it into an Int is lossy - two episodes can collide, and the row cannot be
 * found again afterwards, so "continue watching" could never resume. The key is stored as-is.
 */
data class PlaybackProgress(
    val contentKey: String,
    val kind: ContentKind,
    val title: String,
    val subtitle: String?,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val posterUrl: String?,
    val updatedAtMillis: Long,
    /**
     * The panel's numeric id, meaningful for films and live channels.
     *
     * Carried because it is what the stored row holds, and because a film is still looked up by it.
     * Zero for an episode, which has no numeric id - [contentKey] is the handle there.
     */
    val contentId: Int = 0,
) {
    /**
     * The episode's row key, when this is an episode.
     *
     * This is what makes an episode resumable: the row key is what the episodes table is keyed on,
     * so it is the only handle that can find the episode again.
     */
    val episodeRowKey: String?
        get() = if (kind == ContentKind.EPISODE) {
            contentKey.removePrefix("$kind:")
        } else {
            null
        }

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

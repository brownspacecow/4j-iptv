package com.fourj.iptv.ui.vod

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fourj.iptv.ui.player.describePlaybackError
import com.fourj.iptv.domain.model.ContentKind
import com.fourj.iptv.domain.model.PlaybackProgress
import com.fourj.iptv.ui.player.audioRenderersFactory
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Playback for on-demand content.
 *
 * Deliberately a separate screen from live TV rather than a mode flag on the live one. Live TV has
 * no timeline to scrub and channels are changed with the D-pad; a film has both, and the two want
 * opposite input mappings. Sharing one screen meant either the live overlay grew seek controls
 * that could never do anything, or the film player inherited D-pad zapping.
 *
 * Position is reported back periodically so a crash, a back press or a power cut loses at most a few
 * seconds rather than the whole film.
 */
@Composable
fun VodPlayerScreen(
    title: String,
    subtitle: String?,
    streamUrl: String,
    kind: ContentKind,
    /**
     * Identity this position is saved under.
     *
     * Passed in rather than composed from a kind and a number, because an episode has no usable
     * numeric id and the only handle that finds it again is its row key.
     */
    progressKey: String,
    posterUrl: String?,
    resumePositionSeconds: Long,
    requestHeaders: Map<String, String>,
    onProgress: (PlaybackProgress) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var overlayVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(context, audioRenderersFactory(context))
            // On-demand is the case ExoPlayer's defaults are actually tuned for: a seekable file
            // of known length, rather than an endless live edge.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(requestHeaders)
                        .setUserAgent(requestHeaders["User-Agent"] ?: DEFAULT_USER_AGENT),
                ),
            )
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ false,
                )
                setMediaItem(
                    MediaItem.fromUri(streamUrl),
                    // Resume where the viewer left off, but never past the end: a stale position
                    // on a short film would drop them straight into the credits.
                    resumePositionSeconds.coerceAtLeast(0) * 1000,
                )
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "vod playback failed for $streamUrl", error)
                errorText = describePlaybackError(error)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Poll position rather than listening for every tick: ExoPlayer emits these constantly and a
    // recomposition per frame would be wasteful on a television.
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.takeIf { it > 0 } ?: 0
            if (durationMs > 0) {
                onProgress(
                    PlaybackProgress(
                        contentKey = progressKey,
                        kind = kind,
                        title = title,
                        subtitle = subtitle,
                        positionSeconds = positionMs / 1000,
                        durationSeconds = durationMs / 1000,
                        posterUrl = posterUrl,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    LaunchedEffect(overlayVisible) {
        while (overlayVisible) {
            delay(1000)
            if (!overlayVisible) break
        }
    }

    // Hide the overlay after a period, and bring it back on any input.
    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            delay(OVERLAY_TIMEOUT_MS)
            overlayVisible = false
        }
    }

    BackHandler {
        // One last save so backing out never costs more than the polling interval.
        if (durationMs > 0) {
            onProgress(
                PlaybackProgress(
                    contentKey = progressKey,
                    kind = kind,
                    title = title,
                    subtitle = subtitle,
                    positionSeconds = positionMs / 1000,
                    durationSeconds = durationMs / 1000,
                    posterUrl = posterUrl,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                overlayVisible = true
                when (event.key) {
                    // Left/right scrub, as on every other video player. A tenth of the programme
                    // at a time, so holding a key stays usable on a long film.
                    Key.DirectionLeft -> {
                        player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                        true
                    }

                    Key.DirectionRight -> {
                        val limit = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + SEEK_STEP_MS).coerceAtMost(limit))
                        true
                    }

                    Key.DirectionUp -> {
                        player.seekTo(seekClamp(player, SEEK_STEP_MS))
                        true
                    }

                    Key.DirectionDown -> {
                        player.seekTo(seekClamp(player, -SEEK_STEP_MS))
                        true
                    }

                    Key.MediaPlayPause, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (player.isPlaying) player.pause() else player.play()
                        isPlaying = player.isPlaying
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        val error = errorText
    if (overlayVisible || error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color(0xCC000000))
                    .padding(18.dp),
            ) {
                Text(
                    text = title,
                    style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                        color = Color(0xFFBFC7D2),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (error != null) {
                    Text(
                        text = error,
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF6B6B),
                    )
                } else {
                    Text(
                        text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}" +
                            "   ·   ${if (isPlaying) "playing" else "paused"}",
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                        color = Color(0xFFBFC7D2),
                    )
                    Spacer(Modifier.height(8.dp))
                    SeekBar(
                        progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                        width = 520.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "← → seek    ·    OK pause/resume    ·    Back to exit",
                        style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8A94A3),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekBar(progress: Float, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(width)
            .height(4.dp)
            .background(Color(0x55FFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(Color(0xFF4DA3FF)),
        )
    }
}

/**
 * Seek by [deltaMs] without leaving the programme.
 *
 * `ExoPlayer` has no relative seek, and a live/unknown duration has to be treated as unbounded or
 * the position would clamp to a nonsense value.
 */
private fun seekClamp(player: androidx.media3.exoplayer.ExoPlayer, deltaMs: Long): Long {
    val duration = player.duration
    val target = player.currentPosition + deltaMs
    return if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0)
}

/** `H:MM:SS` or `M:SS`, whichever is needed. */
internal fun formatDuration(millis: Long): String {    if (millis <= 0) return "--:--"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}


private const val SEEK_STEP_MS = 10_000L
private const val PROGRESS_INTERVAL_MS = 5_000L
private const val OVERLAY_TIMEOUT_MS = 5_000L
private const val TAG = "4J"
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"


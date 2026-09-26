package com.fourj.iptv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fourj.iptv.domain.model.LiveChannel
import kotlinx.coroutines.delay

/**
 * Full-screen playback.
 *
 * Two things here are specifically for a remote control:
 *
 *  - **Channel zapping.** Up and down change channel directly, the way a set-top box does. Without
 *    this, changing channel means backing out to a list, finding the next entry and confirming it.
 *  - **The built-in Media3 controller is off.** It is laid out for touch; on a television its
 *    scrub bar and buttons are the wrong size and in the wrong places, so this draws its own
 *    minimal overlay and leaves D-pad up/down/left/right free for zapping.
 */
@Composable
fun PlayerScreen(
    channel: LiveChannel,
    channels: List<LiveChannel>,
    streamUrl: String,
    requestHeaders: Map<String, String>,
    onChannelChange: (LiveChannel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val rootFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var overlayVisible by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // A TV screen that blanks mid-film is worse than one that is slightly power hungry.
    val activity = context as? android.app.Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val player = remember {
        ExoPlayer.Builder(context, audioRenderersFactory(context))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    // Panel-issued headers matter: without the User-Agent/Referer a channel
                    // expects, a fair number of streams simply answer 403.
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(requestHeaders)
                        .setUserAgent(requestHeaders["User-Agent"] ?: DEFAULT_USER_AGENT),
                ),
            )
            .build()
            .apply {
                // Providers buffer aggressively; a longer buffer hides the stalls that would
                // otherwise show as a freeze every few seconds.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    // Without this ExoPlayer does not participate in audio focus at all, which
                    // on a television means the app can talk over a system sound or another
                    // player instead of ducking for it.
                    /* handleAudioFocus = */ true,
                )
                setMediaItem(MediaItem.fromUri(streamUrl))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = "This channel would not play. It may be offline or restricted."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Channel change. Tearing the old source down first is not belt-and-braces: calling
    // setMediaItem() on a still-playing live MPEG-TS stream leaves the previous source feeding
    // the audio decoder, which is heard as the old channel still audible - often looping -
    // underneath the new one. stop() releases the decoder and the AudioTrack; clearMediaItems()
    // drops the buffered segments that would otherwise be replayed.
    LaunchedEffect(streamUrl) {
        errorText = null
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            delay(OVERLAY_TIMEOUT_MS)
            overlayVisible = false
        }
    }

    // Wake the overlay whenever the user does something.
    LaunchedEffect(channel.streamId) { overlayVisible = true }

    BackHandler { onBack() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        zapped(channels, channel, -1)?.let(onChannelChange)
                        overlayVisible = true
                        true
                    }

                    Key.DirectionDown -> {
                        zapped(channels, channel, 1)?.let(onChannelChange)
                        overlayVisible = true
                        true
                    }

                    else -> {
                        overlayVisible = true
                        false
                    }
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    // No touch control bar - this screen handles its own input.
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible || errorText != null) {
            PlayerOverlay(
                channelName = channel.name,
                error = errorText,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
            )
        }
    }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }
}

@Composable
private fun PlayerOverlay(
    channelName: String,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) {
            androidx.tv.material3.Text(
                text = error,
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFF6B6B),
            )
        } else {
            androidx.tv.material3.Text(
                text = channelName,
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

private const val OVERLAY_TIMEOUT_MS = 4_000L
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

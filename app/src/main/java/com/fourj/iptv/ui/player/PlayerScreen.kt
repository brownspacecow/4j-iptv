package com.fourj.iptv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
    nowNext: com.fourj.iptv.ui.live.NowNext?,
    onChannelChange: (LiveChannel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val rootFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var overlayVisible by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Ticks while the overlay is up so the elapsed-time bar moves, then stops.
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            while (overlayVisible) {
                nowSeconds = System.currentTimeMillis() / 1000
                delay(1000)
            }
        }
    }

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
            // Tuned for live IPTV, not for on-demand video.
            //
            // ExoPlayer's defaults assume a seekable, well-behaved file: 2.5s of buffer before
            // playback starts and 5s to rebuild after a stall. A live MPEG-TS feed from a panel
            // is none of those things - it arrives at whatever rate the origin feels like, and
            // starting only 2.5s from the live edge means the first seconds of playback spend
            // most of their time with an empty buffer. That is the stutter reported a few
            // seconds after picking a channel.
            //
            // A few seconds of extra delay at startup is a good trade for not stuttering.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 50_000,
                        /* bufferForPlaybackMs = */ 5_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 10_000,
                    )
                    // Never evict samples to hit a byte target. On a live stream that would
                    // mean discarding data and creating the very gaps we are avoiding.
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    // Panel-issued headers matter: without the User-Agent/Referer a channel
                    // expects, a fair number of streams simply answer 403.
                    //
                    // Cross-protocol redirects are followed deliberately. Media3 blocks them by
                    // default, which is the right default in general - a redirect that changes
                    // scheme should not silently forward credentials to a new origin - but panels
                    // routinely serve media from an https CDN and answer 302, and refusing that
                    // makes the content unplayable. Verified against a real provider, where every
                    // series episode resolved to a 302 and refused to start. The headers are the
                    // viewer's own provider credentials going to that provider's own CDN, which is
                    // the same destination the panel was already pointing at.
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(requestHeaders)
                        .setUserAgent(requestHeaders["User-Agent"] ?: DEFAULT_USER_AGENT)
                        .setAllowCrossProtocolRedirects(true),
                ),
            )
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    // Deliberately NOT handling audio focus.
                    //
                    // Handling it means an abandon/request pair on every single channel change -
                    // two binder round trips to the audio service each time you zap, and the
                    // service briefly has no idea who is playing. Zapping is this app's primary
                    // navigation, so that cost is paid constantly, and the churn is a plausible
                    // source of the audible glitching on channel change.
                    //
                    // The trade-off accepted: 4J TV will talk over a system sound rather than
                    // ducking for it. For a full-screen television app that is the right way
                    // round - a notification chime should not pause the programme.
                    /* handleAudioFocus = */ false,
                )
                setMediaItem(MediaItem.fromUri(streamUrl))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {

            override fun onPlayerError(error: PlaybackException) {
                // Always log the cause. The previous version swallowed it and showed a generic
                // message, which made a channel that was merely unreachable look identical to a
                // channel that was genuinely dead - and left nothing to debug with.
                Log.w(TAG, "playback failed for $streamUrl", error)
                errorText = describePlaybackError(error)
            }

            /**
             * Report what the stream actually carries.
             *
             * Some channels arrive with no audio at all, and some carry audio the device cannot
             * decode. Both look identical on screen - a silent picture - so the format and the
             * selected track are logged to tell them apart.
             */
            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                if (audio == null || audio.length == 0) {
                    Log.w(TAG, "stream advertises no audio track at all (groups=${tracks.groups.size})")
                    return
                }
                // Log every track in the group, selected or not. "Present but not selected" is
                // the interesting case: the stream has audio and the device refused it, so the
                // format is what identifies the problem.
                for (i in 0 until audio.length) {
                    val f = audio.getTrackFormat(i)
                    Log.i(
                        TAG,
                        "audio[$i] mime=${f.sampleMimeType} rate=${f.sampleRate} " +
                            "channels=${f.channelCount} supported=${audio.isTrackSupported(i)} " +
                            "selected=${audio.isTrackSelected(i)} " +
                            "typeSelected=${tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)}",
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    Log.i(TAG, "buffering (target ${player.bufferedPercentage}% buffered)")
                }
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
    LaunchedEffect(channel.streamId) {
        overlayVisible = true
        nowSeconds = System.currentTimeMillis() / 1000
    }

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
                nowNext = nowNext,
                nowSeconds = nowSeconds,
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
    nowNext: com.fourj.iptv.ui.live.NowNext?,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (error != null) {
            androidx.tv.material3.Text(
                text = error,
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFF6B6B),
            )
            return@Column
        }
        // The guide goes in the overlay rather than replacing the channel name: the name
        // identifies what you are watching, the programme says why you are still watching it.
        com.fourj.iptv.ui.epg.NowNextRow(
            channelName = channelName,
            nowNext = nowNext,
            nowSeconds = nowSeconds,
        )
    }
}

private const val OVERLAY_TIMEOUT_MS = 4_000L
private const val TAG = "4J"
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

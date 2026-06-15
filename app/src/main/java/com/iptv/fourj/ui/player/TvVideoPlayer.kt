package com.iptv.fourj.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun TvVideoPlayer(
    streamUrl: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(streamUrl) {
        val exoPlayer = ExoPlayer.Builder(context).build().also {
            it.setMediaItem(MediaItem.fromUri(streamUrl))
            it.prepare()
            it.playWhenReady = true
            it.addListener(object : Player.Listener {
                override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                }
            })
        }
        player = exoPlayer

        onDispose {
            exoPlayer.release()
            player = null
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setControllerAutoShow(true)
                setControllerHideOnTouch(false)
                setControllerShowTimeoutMs(5000)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier,
        update = { view ->
            player?.let { view.player = it }
        }
    )
}

package com.iptv.fourj.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.fourj.R

fun nextResizeMode(current: Int): Int = when (current) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

fun resizeModeLabel(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> "FIT"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "FILL"
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "FIXW"
    AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "FIXH"
    else -> "FIT"
}

@Composable
fun TvVideoPlayer(
    streamUrl: String,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onResizeModeChange: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    val currentResizeMode by rememberUpdatedState(resizeMode)

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
                this.resizeMode = currentResizeMode
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                val aspectButton = findViewById<TextView>(R.id.exo_aspect_ratio)
                aspectButton?.text = resizeModeLabel(currentResizeMode)
                aspectButton?.setOnClickListener {
                    val next = nextResizeMode(currentResizeMode)
                    onResizeModeChange?.invoke(next)
                }
            }
        },
        modifier = modifier,
        update = { view ->
            view.resizeMode = currentResizeMode
            view.useController = true
            player?.let { view.player = it }
            val aspectButton = view.findViewById<TextView>(R.id.exo_aspect_ratio)
            aspectButton?.text = resizeModeLabel(currentResizeMode)
            aspectButton?.setOnClickListener {
                val next = nextResizeMode(currentResizeMode)
                onResizeModeChange?.invoke(next)
            }
        }
    )
}

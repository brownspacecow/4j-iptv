package com.iptv.fourj.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.media3.ui.AspectRatioFrameLayout
import com.iptv.fourj.ui.player.TvVideoPlayer
import com.iptv.fourj.ui.player.resizeModeLabel
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

@Composable
fun PlayerScreen(streamUrl: String, title: String, navController: NavHostController) {
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TvVideoPlayer(
            streamUrl = streamUrl,
            resizeMode = resizeMode,
            modifier = Modifier.fillMaxSize(),
            onBack = { navController.popBackStack() },
            onResizeModeChange = { next ->
                resizeMode = next
                Toast.makeText(context, "Aspect: ${resizeModeLabel(next)}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

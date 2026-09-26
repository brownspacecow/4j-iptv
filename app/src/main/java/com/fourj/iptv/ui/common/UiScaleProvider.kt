package com.fourj.iptv.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.fourj.iptv.ui.theme.UiScale

/**
 * Work out how much room this device has.
 *
 * Checks the UI mode rather than screen size alone: a Google TV or Chromecast reports a modest
 * resolution but needs television treatment, while a large tablet in portrait does not.
 */
@Composable
fun rememberUiScale(): UiScale {
    val configuration: Configuration = LocalConfiguration.current
    return remember(configuration.uiMode, configuration.smallestScreenWidthDp) {
        val uiMode = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        when {
            uiMode == Configuration.UI_MODE_TYPE_TELEVISION ->
                UiScale(isTelevision = true, isTablet = false)

            configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP ->
                UiScale(isTelevision = false, isTablet = true)

            else -> UiScale(isTelevision = false, isTablet = false)
        }
    }
}

private const val TABLET_SMALLEST_WIDTH_DP = 600

package com.fourj.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fourj.iptv.di.AppContainer
import com.fourj.iptv.ui.common.rememberUiScale
import com.fourj.iptv.ui.live.LiveScreen
import com.fourj.iptv.ui.login.LoginScreen
import com.fourj.iptv.ui.theme.FourJTheme
import com.fourj.iptv.domain.model.ProviderProfile

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as FourJApp).container

        setContent {
            val uiScale = rememberUiScale()
            FourJTheme(uiScale = uiScale) {
                FourJRoot(container)
            }
        }
    }
}

/**
 * Decides between the connect screen and live TV.
 *
 * Plain state rather than a navigation graph: there are only two destinations and one of them is
 * the whole app, so a back stack would be ceremony without benefit. Kept separate from
 * [MainActivity] so the destination logic can be read - and changed - in one place.
 */
@Composable
private fun FourJRoot(container: AppContainer) {
    var profile: ProviderProfile? by remember { mutableStateOf(container.credentialStore.load()) }

    val current = profile
    if (current == null) {
        LoginScreen(
            container = container,
            onConnected = { profile = container.credentialStore.load() },
        )
    } else {
        LiveScreen(
            container = container,
            profile = current,
            onSignOut = {
                container.credentialStore.clear()
                profile = null
            },
        )
    }
}

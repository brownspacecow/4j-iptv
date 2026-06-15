package com.iptv.fourj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.iptv.fourj.ui.navigation.NavGraph
import com.iptv.fourj.ui.theme.FourJIptvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FourJIptvTheme {
                val app = application as FourJApp
                val navController = rememberNavController()
                NavGraph(
                    navController = navController,
                    repository = app.repository,
                    providerStore = app.providerStore
                )
            }
        }
    }
}

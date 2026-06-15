package com.iptv.fourj.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.iptv.fourj.data.db.ProviderStore
import com.iptv.fourj.ui.navigation.Routes
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(navController: NavHostController, providerStore: ProviderStore) {
    LaunchedEffect(Unit) {
        val provider = providerStore.getActiveProvider()
        delay(1000)
        if (provider != null) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.SPLASH) { inclusive = true }
            }
        } else {
            navController.navigate(Routes.PROVIDERS) {
                popUpTo(Routes.SPLASH) { inclusive = true }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                Text(
                    text = "4",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "J",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "IPTV",
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 6.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

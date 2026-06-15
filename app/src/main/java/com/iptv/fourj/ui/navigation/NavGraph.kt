package com.iptv.fourj.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.iptv.fourj.ui.screens.*

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val PROVIDERS = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val LIVE = "live"
    const val LIVE_CATEGORY = "live_category/{categoryId}/{categoryName}"
    const val MOVIES = "movies"
    const val MOVIES_CATEGORY = "movies_category/{categoryId}/{categoryName}"
    const val SERIES = "series"
    const val SERIES_CATEGORY = "series_category/{categoryId}/{categoryName}"
    const val SERIES_DETAIL = "series_detail/{seriesId}"
    const val PLAYER = "player/{streamUrl}/{title}"

    fun liveCategory(categoryId: String, categoryName: String) =
        "live_category/$categoryId/$categoryName"

    fun moviesCategory(categoryId: String, categoryName: String) =
        "movies_category/$categoryId/$categoryName"

    fun seriesCategory(categoryId: String, categoryName: String) =
        "series_category/$categoryId/$categoryName"

    fun seriesDetail(seriesId: String) = "series_detail/$seriesId"

    fun player(streamUrl: String, title: String) =
        "player/${java.net.URLEncoder.encode(streamUrl, "UTF-8")}/${java.net.URLEncoder.encode(title, "UTF-8")}"
}

@Composable
fun NavGraph(navController: NavHostController, repository: com.iptv.fourj.data.repository.IptvRepository, providerStore: com.iptv.fourj.data.db.ProviderStore) {
    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(navController, providerStore)
        }
        composable(Routes.HOME) {
            HomeScreen(navController, repository)
        }
        composable(Routes.PROVIDERS) {
            ProvidersScreen(navController, providerStore)
        }
        composable(Routes.ADD_PROVIDER) {
            AddProviderScreen(navController, providerStore, repository)
        }
        composable(Routes.LIVE) {
            LiveCategoriesScreen(navController, repository)
        }
        composable(
            Routes.LIVE_CATEGORY,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.StringType },
                navArgument("categoryName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getString("categoryId") ?: ""
            val categoryName = backStackEntry.arguments?.getString("categoryName") ?: ""
            LiveChannelScreen(navController, repository, categoryId, categoryName)
        }
        composable(Routes.MOVIES) {
            MoviesCategoriesScreen(navController, repository)
        }
        composable(
            Routes.MOVIES_CATEGORY,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.StringType },
                navArgument("categoryName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getString("categoryId") ?: ""
            val categoryName = backStackEntry.arguments?.getString("categoryName") ?: ""
            MoviesListScreen(navController, repository, categoryId, categoryName)
        }
        composable(Routes.SERIES) {
            SeriesCategoriesScreen(navController, repository)
        }
        composable(
            Routes.SERIES_CATEGORY,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.StringType },
                navArgument("categoryName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getString("categoryId") ?: ""
            val categoryName = backStackEntry.arguments?.getString("categoryName") ?: ""
            SeriesListScreen(navController, repository, categoryId, categoryName)
        }
        composable(
            Routes.SERIES_DETAIL,
            arguments = listOf(navArgument("seriesId") { type = NavType.StringType })
        ) { backStackEntry ->
            val seriesId = backStackEntry.arguments?.getString("seriesId") ?: ""
            SeriesDetailScreen(navController, repository, seriesId)
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val streamUrl = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("streamUrl") ?: "", "UTF-8"
            )
            val title = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("title") ?: "", "UTF-8"
            )
            PlayerScreen(streamUrl, title, navController)
        }
    }
}

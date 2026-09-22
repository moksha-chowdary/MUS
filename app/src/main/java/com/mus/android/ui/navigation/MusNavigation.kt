package com.mus.android.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mus.android.data.model.Track
import com.mus.android.ui.screens.*

sealed class MusRoute(val route: String) {
    data object Home : MusRoute("home")
    data object Search : MusRoute("search")
    data object Library : MusRoute("library")
    data object NowPlaying : MusRoute("now_playing")
    data object Album : MusRoute("album/{albumId}") {
        fun create(albumId: Long) = "album/$albumId"
    }
    data object Artist : MusRoute("artist/{artistId}") {
        fun create(artistId: Long) = "artist/$artistId"
    }
    data object Playlist : MusRoute("playlist/{playlistId}") {
        fun create(playlistId: Long) = "playlist/$playlistId"
    }
    data object Settings : MusRoute("settings")
    data object DownloadQueue : MusRoute("download_queue")
}

@Composable
fun MusNavHost(
    navController: NavHostController,
    onTrackClick: (Track, List<Track>) -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = MusRoute.Home.route,
        modifier = modifier,
    ) {
        composable(MusRoute.Home.route) {
            HomeScreen(
                onAlbumClick = { navController.navigate(MusRoute.Album.create(it)) },
                onPlaylistClick = { navController.navigate(MusRoute.Playlist.create(it)) },
                onSettingsClick = { navController.navigate(MusRoute.Settings.route) },
                onTrackClick = onTrackClick,
            )
        }

        composable(MusRoute.Search.route) {
            SearchScreen(
                onTrackClick = onTrackClick,
                onAlbumClick = { navController.navigate(MusRoute.Album.create(it)) },
                onArtistClick = { navController.navigate(MusRoute.Artist.create(it)) },
                onDownloadQueueClick = { navController.navigate(MusRoute.DownloadQueue.route) },
            )
        }

        composable(MusRoute.Library.route) {
            LibraryScreen(
                onAlbumClick = { navController.navigate(MusRoute.Album.create(it)) },
                onArtistClick = { navController.navigate(MusRoute.Artist.create(it)) },
                onPlaylistClick = { navController.navigate(MusRoute.Playlist.create(it)) },
                onTrackClick = onTrackClick,
            )
        }

        composable(
            route = MusRoute.Album.route,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
        ) {
            AlbumScreen(
                onBack = { navController.popBackStack() },
                onTrackSelected = { navController.navigate(MusRoute.NowPlaying.route) },
            )
        }

        composable(
            route = MusRoute.Artist.route,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
        ) {
            ArtistScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { navController.navigate(MusRoute.Album.create(it)) },
            )
        }

        composable(
            route = MusRoute.Playlist.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) {
            PlaylistScreen(
                onBack = { navController.popBackStack() },
                onTrackSelected = { navController.navigate(MusRoute.NowPlaying.route) },
            )
        }

        composable(
            route = MusRoute.NowPlaying.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(360, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                ) + fadeIn(tween(250))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300, easing = CubicBezierEasing(0.4f, 0f, 1f, 1f))
                ) + fadeOut(tween(200))
            },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300, easing = CubicBezierEasing(0.4f, 0f, 1f, 1f))
                ) + fadeOut(tween(200))
            },
        ) {
            NowPlayingScreen(
                onBack = { navController.popBackStack() },
                onQueueClick = onQueueClick,
            )
        }

        composable(MusRoute.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(MusRoute.DownloadQueue.route) {
            DownloadQueueScreen(onBack = { navController.popBackStack() })
        }
    }
}

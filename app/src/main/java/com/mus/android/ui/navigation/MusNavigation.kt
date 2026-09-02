package com.mus.android.ui.navigation

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
}

@Composable
fun MusNavHost(
    navController: NavHostController,
    onTrackClick: (Track, List<Track>) -> Unit,
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
                onTrackClick = onTrackClick,
            )
        }

        composable(MusRoute.Search.route) {
            SearchScreen(
                onTrackClick = onTrackClick,
                onAlbumClick = { navController.navigate(MusRoute.Album.create(it)) },
                onArtistClick = { navController.navigate(MusRoute.Artist.create(it)) },
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
            AlbumScreen(onBack = { navController.popBackStack() })
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
            PlaylistScreen(onBack = { navController.popBackStack() })
        }

        composable(MusRoute.NowPlaying.route) {
            NowPlayingScreen(
                onBack = { navController.popBackStack() },
                onQueueClick = { /* Queue sheet handled in MusApp */ },
            )
        }
    }
}

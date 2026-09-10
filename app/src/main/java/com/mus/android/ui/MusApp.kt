package com.mus.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mus.android.ui.ambient.AmbientGradientBackground
import com.mus.android.ui.components.MiniPlayer
import com.mus.android.ui.navigation.MusNavHost
import com.mus.android.ui.navigation.MusRoute
import com.mus.android.ui.screens.NowPlayingScreen
import com.mus.android.ui.screens.QueueSheet
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.viewmodel.NowPlayingViewModel

@Composable
fun MusApp(
    modifier: Modifier = Modifier,
    nowPlayingViewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val currentTrack by nowPlayingViewModel.currentTrack.collectAsState()
    val isPlaying by nowPlayingViewModel.isPlaying.collectAsState()
    val artworkColors by nowPlayingViewModel.artworkColors.collectAsState()

    var showNowPlaying by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Box(modifier = modifier.fillMaxSize().background(MusColors.Background)) {
        // Global ambient background — subtle flowing colors derived from current artwork
        AmbientGradientBackground(
            colors = artworkColors,
            intensity = 0.12f,
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                MusNavHost(
                    navController = navController,
                    onTrackClick = { track, queue ->
                        nowPlayingViewModel.playTrackWithQueue(track, queue)
                    },
                )
            }

            // Mini-player (self-contained position observer)
            AnimatedVisibility(
                visible = currentTrack != null && !showNowPlaying,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                currentTrack?.let { track ->
                    MiniPlayer(
                        track = track,
                        isPlaying = isPlaying,
                        viewModel = nowPlayingViewModel,
                        onTap = { showNowPlaying = true },
                        onPlayPause = { nowPlayingViewModel.togglePlayPause() },
                        onSkipNext = { nowPlayingViewModel.skipNext() },
                    )
                }
            }

            // Bottom navigation
            NavigationBar(
                containerColor = MusColors.Surface,
                contentColor = MusColors.OnBackground,
                tonalElevation = 0.dp,
            ) {
                val items = listOf(
                    Triple(MusRoute.Home.route, Icons.Rounded.Home, "Home"),
                    Triple(MusRoute.Search.route, Icons.Rounded.Search, "Search"),
                    Triple(MusRoute.Library.route, Icons.Rounded.LibraryMusic, "Library"),
                )
                items.forEach { (route, icon, label) ->
                    val selected = currentRoute == route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != route) {
                                navController.navigate(route) {
                                    popUpTo(MusRoute.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
                        },
                        label = {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MusColors.OnBackground,
                            selectedTextColor = MusColors.OnBackground,
                            unselectedIconColor = MusColors.OnBackgroundTertiary,
                            unselectedTextColor = MusColors.OnBackgroundTertiary,
                            indicatorColor = MusColors.SurfaceVariant,
                        ),
                    )
                }
            }
        }

        // Now Playing overlay
        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(
                onBack = { showNowPlaying = false },
                onQueueClick = { showQueue = true },
            )
        }

        if (showQueue) {
            QueueSheet(onDismiss = { showQueue = false })
        }
    }
}

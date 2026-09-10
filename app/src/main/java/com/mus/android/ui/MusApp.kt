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

    var showQueue by remember { mutableStateOf(false) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isNowPlayingRoute = currentRoute == MusRoute.NowPlaying.route
    val isMainTab = currentRoute in listOf(MusRoute.Home.route, MusRoute.Search.route, MusRoute.Library.route)

    // Dynamic ambient background intensity:
    // Full atmospheric lighting (0.95f) on Now Playing, elegant subtle glow (0.55f) across other screens
    val ambientIntensity = if (isNowPlayingRoute) {
        0.95f
    } else if (currentTrack != null) {
        0.55f
    } else {
        0.30f
    }

    Box(modifier = modifier.fillMaxSize().background(MusColors.Background)) {
        // Global ambient background — softly glowing colors derived from current album artwork
        AmbientGradientBackground(
            colors = artworkColors,
            intensity = ambientIntensity,
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                MusNavHost(
                    navController = navController,
                    onTrackClick = { track, queue ->
                        nowPlayingViewModel.playTrackWithQueue(track, queue)
                        navController.navigate(MusRoute.NowPlaying.route)
                    },
                    onQueueClick = { showQueue = true },
                )
            }

            // Mini-player — visible when music is loaded and not on full Now Playing screen
            AnimatedVisibility(
                visible = currentTrack != null && !isNowPlayingRoute,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                currentTrack?.let { track ->
                    MiniPlayer(
                        track = track,
                        isPlaying = isPlaying,
                        viewModel = nowPlayingViewModel,
                        onTap = { navController.navigate(MusRoute.NowPlaying.route) },
                        onPlayPause = { nowPlayingViewModel.togglePlayPause() },
                        onSkipNext = { nowPlayingViewModel.skipNext() },
                    )
                }
            }

            // Bottom navigation — visible on primary navigation tabs
            if (isMainTab) {
                NavigationBar(
                    containerColor = MusColors.Surface.copy(alpha = 0.85f),
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
        }

        if (showQueue) {
            QueueSheet(onDismiss = { showQueue = false })
        }
    }
}


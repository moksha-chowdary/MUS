package com.mus.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
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

private data class MusTab(
    val route: String,
    val icon: ImageVector,
    val label: String,
)

/**
 * App shell. The ambient artwork-derived gradient (kept from the original MUS identity)
 * glows behind an Apple Music–style chrome: floating mini-player pill + translucent tab bar.
 */
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
    val tabs = listOf(
        MusTab(MusRoute.Home.route, Icons.Rounded.Home, "Home"),
        MusTab(MusRoute.Search.route, Icons.Rounded.Search, "Search"),
        MusTab(MusRoute.Library.route, Icons.Rounded.LibraryMusic, "Library"),
    )
    val isMainTab = currentRoute in tabs.map { it.route }

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

            // Bottom navigation — translucent Apple Music–style tab bar on primary tabs
            if (isMainTab) {
                MusTabBar(
                    tabs = tabs,
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        if (currentRoute != route) {
                            navController.navigate(route) {
                                popUpTo(MusRoute.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }

        if (showQueue) {
            QueueSheet(onDismiss = { showQueue = false })
        }
    }
}

@Composable
private fun MusTabBar(
    tabs: List<MusTab>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MusColors.Surface.copy(alpha = 0.88f))
            .navigationBarsPadding()
            .padding(top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            val alpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0.42f,
                animationSpec = spring(stiffness = 500f),
                label = "tab_${tab.route}",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = MutableInteractionSource(),
                        indication = null,
                    ) { onSelect(tab.route) }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    modifier = Modifier.size(23.dp).alpha(alpha),
                    tint = MusColors.OnBackground,
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MusColors.OnBackground,
                    modifier = Modifier.padding(top = 1.dp).alpha(alpha),
                )
            }
        }
    }
}

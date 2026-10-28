package com.mus.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mus.android.ui.components.CookiePasteDialog
import com.mus.android.ui.components.YouTubeLoginDialog
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.HomeViewModel
import com.mus.android.ui.viewmodel.YtAccountViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
    ytAccountViewModel: YtAccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isLoading by homeViewModel.isLoading.collectAsState()
    val albums by homeViewModel.albums.collectAsState()
    val recentlyAdded by homeViewModel.recentlyAdded.collectAsState()

    val ytConnection by ytAccountViewModel.connection.collectAsState()
    var showYtLoginDialog by remember { mutableStateOf(false) }
    var showCookiePasteDialog by remember { mutableStateOf(false) }
    var cookiePasteError by remember { mutableStateOf<String?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            homeViewModel.setCustomMuzicFolder(treeUri)
        }
    }

    var audioFocusEnabled by remember { mutableStateOf(homeViewModel.isAudioFocusEnabled()) }
    var waveformEnabled by remember { mutableStateOf(true) }
    var automaticMetadataEnabled by remember { mutableStateOf(homeViewModel.isAutomaticMetadataEnabled()) }

    val muzicFolderExists = remember(isLoading) {
        homeViewModel.doesMuzicDirectoryExist()
    }
    val customVaultUri = homeViewModel.getCustomMuzicUri()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = MusColors.OnBackground,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MusColors.OnBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MusColors.Background,
                ),
            )
        },
        containerColor = MusColors.Background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.base, vertical = Spacing.md)
        ) {
            // Storage Section
            SettingsSectionHeader("Storage & Ingestion")

            Card(
                colors = CardDefaults.cardColors(containerColor = MusColors.SurfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = MusColors.OnBackground,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Local Music Vault",
                                style = MaterialTheme.typography.titleSmall,
                                color = MusColors.OnBackground,
                            )
                            Text(
                                customVaultUri ?: "/storage/emulated/0/Muzic/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MusColors.OnBackgroundSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.sm))

                    Text(
                        if (muzicFolderExists || customVaultUri != null) "Status: Ready • ${recentlyAdded.size} songs found"
                        else "Status: /Muzic/ folder not detected on device",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (muzicFolderExists || customVaultUri != null) MusColors.OnBackgroundSecondary else MusColors.Error,
                    )

                    Spacer(Modifier.height(Spacing.md))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        if (!muzicFolderExists && customVaultUri == null) {
                            Button(
                                onClick = { homeViewModel.createMuzicDirectory() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MusColors.OnBackground,
                                    contentColor = MusColors.Background,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Create Folder")
                            }
                        }

                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MusColors.OnBackground,
                            ),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Choose Folder")
                        }

                        OutlinedButton(
                            onClick = { homeViewModel.refreshLibrary() },
                            enabled = !isLoading,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MusColors.OnBackground,
                            ),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                    color = MusColors.OnBackground,
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text("Scanning...")
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(Spacing.xs))
                                Text("Rescan")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            // Permissions Section
            SettingsSectionHeader("Permissions")

            SettingsActionItem(
                icon = Icons.Rounded.Security,
                title = "Android Media Access",
                subtitle = "Manage audio storage access in system settings",
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var allFilesGranted by remember {
                    mutableStateOf(Environment.isExternalStorageManager())
                }
                LifecycleResumeEffect(Unit) {
                    allFilesGranted = Environment.isExternalStorageManager()
                    onPauseOrDispose { }
                }
                SettingsActionItem(
                    icon = if (allFilesGranted) Icons.Rounded.FolderSpecial else Icons.Rounded.Folder,
                    title = if (allFilesGranted) "All Files Access — Granted" else "Allow All Files Access",
                    subtitle = if (allFilesGranted) "Downloads land directly in your /Muzic/ folder"
                        else "Required so downloads land directly in your /Muzic/ folder",
                    onClick = {
                        if (!allFilesGranted) {
                            val launched = runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            }.isSuccess
                            if (!launched) {
                                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            // Playback Preferences
            SettingsSectionHeader("Playback")

            SettingsToggleItem(
                icon = Icons.Rounded.Headphones,
                title = "Audio Focus Handling",
                subtitle = "Pause playback when incoming calls or other audio starts",
                checked = audioFocusEnabled,
                onCheckedChange = {
                    audioFocusEnabled = it
                    homeViewModel.setAudioFocusEnabled(it)
                },
            )

            SettingsToggleItem(
                icon = Icons.Rounded.GraphicEq,
                title = "Live Waveform Morphing",
                subtitle = "Show animated real-amplitude waveform on Now Playing",
                checked = waveformEnabled,
                onCheckedChange = { waveformEnabled = it },
            )

            Spacer(Modifier.height(Spacing.xl))

            // Metadata Enrichment Section
            SettingsSectionHeader("Metadata Enrichment")

            SettingsToggleItem(
                icon = Icons.Rounded.AutoAwesome,
                title = "Automatic Metadata",
                subtitle = "Automatically find missing song information and artwork",
                checked = automaticMetadataEnabled,
                onCheckedChange = {
                    automaticMetadataEnabled = it
                    homeViewModel.setAutomaticMetadataEnabled(it)
                },
            )

            SettingsActionItem(
                icon = Icons.Rounded.Sync,
                title = "Refresh Metadata",
                subtitle = "Re-run enrichment for incomplete tracks and artwork",
                onClick = {
                    homeViewModel.refreshMetadata()
                }
            )

            SettingsActionItem(
                icon = Icons.Rounded.RestartAlt,
                title = "Re-verify Library Metadata",
                subtitle = "Re-evaluates uncertain matches and clears wrong remote covers",
                onClick = {
                    homeViewModel.reverifyLibrary()
                    android.widget.Toast.makeText(
                        context,
                        "Re-verifying library metadata...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )

            Spacer(Modifier.height(Spacing.xl))

            // YouTube Music Account Section (cookie auth — ported from LastWave)
            SettingsSectionHeader("YouTube Music Account")

            Card(
                colors = CardDefaults.cardColors(containerColor = MusColors.SurfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = MusColors.OnBackground,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (ytConnection.isConnected) "Connected"
                                else "Not connected",
                                style = MaterialTheme.typography.titleSmall,
                                color = MusColors.OnBackground,
                            )
                            Text(
                                if (ytConnection.isConnected)
                                    "${ytConnection.accountName} — library, likes and history use your account"
                                else
                                    "Sign in to browse YouTube Music with your account (optional)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MusColors.OnBackgroundSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!ytConnection.isConnected) {
                            Button(onClick = { showYtLoginDialog = true }) {
                                Text("Sign in")
                            }
                            OutlinedButton(onClick = {
                                cookiePasteError = null
                                showCookiePasteDialog = true
                            }) {
                                Text("Paste cookies")
                            }
                        } else {
                            OutlinedButton(onClick = {
                                ytAccountViewModel.signOut()
                            }) {
                                Text("Sign out")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            // About Section
            SettingsSectionHeader("About")

            Card(
                colors = CardDefaults.cardColors(containerColor = MusColors.SurfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text(
                        "MUS",
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "Version 0.1.0 • Nothing-inspired Acoustic Player",
                        style = MaterialTheme.typography.bodySmall,
                        color = MusColors.OnBackgroundSecondary,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "Strictly scopes local music to /Muzic/ with Media3 playback and Room SQLite persistence.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MusColors.OnBackgroundTertiary,
                    )
                }
            }

            Spacer(Modifier.height(100.dp))
        }
    }

    if (showYtLoginDialog) {
        YouTubeLoginDialog(
            onDismiss = { showYtLoginDialog = false },
            onCookiesCaptured = { cookies ->
                showYtLoginDialog = false
                ytAccountViewModel.connectCookies(cookies) { ok, message ->
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (showCookiePasteDialog) {
        CookiePasteDialog(
            onDismiss = { showCookiePasteDialog = false },
            error = cookiePasteError,
            onSubmit = { raw ->
                ytAccountViewModel.connectCookies(raw) { ok, message ->
                    if (ok) {
                        showCookiePasteDialog = false
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        cookiePasteError = message
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MusColors.OnBackgroundSecondary,
        modifier = Modifier.padding(vertical = Spacing.sm),
    )
}

@Composable
private fun SettingsActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MusColors.SurfaceVariant, RoundedCornerShape(12.dp))
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MusColors.OnBackground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MusColors.OnBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MusColors.OnBackgroundSecondary)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MusColors.OnBackgroundTertiary)
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MusColors.SurfaceVariant, RoundedCornerShape(12.dp))
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MusColors.OnBackground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MusColors.OnBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MusColors.OnBackgroundSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MusColors.Background,
                checkedTrackColor = MusColors.OnBackground,
                uncheckedThumbColor = MusColors.OnBackgroundTertiary,
                uncheckedTrackColor = MusColors.SurfaceElevated,
            )
        )
    }
    Spacer(Modifier.height(Spacing.sm))
}

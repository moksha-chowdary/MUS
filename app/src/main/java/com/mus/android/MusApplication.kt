package com.mus.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mus.android.data.music.InnerTubeMusicApi
import com.mus.android.data.music.potoken.BotGuardTokenGenerator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class MusApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoaderProvider: Provider<ImageLoader>

    @Inject
    lateinit var innerTube: dagger.Lazy<InnerTubeMusicApi>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        // Initialize the BotGuard PoToken engine (headless WebView is created
        // lazily on first mint, never at startup) and warm the InnerTube web
        // config so the first search/playback resolves quickly.
        BotGuardTokenGenerator.initialize(this)
        appScope.launch {
            runCatching { innerTube.get().preWarmPlayback() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoaderProvider.get()
    }
}

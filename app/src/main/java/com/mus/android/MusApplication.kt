package com.mus.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class MusApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoaderProvider: Provider<ImageLoader>

    override fun onCreate() {
        super.onCreate()
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoaderProvider.get()
    }
}

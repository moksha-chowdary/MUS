package com.mus.android.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Handles the "Cancel" action on download progress notifications. */
@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadManager: TrackDownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TrackDownloadManager.ACTION_CANCEL_DOWNLOAD) return
        val key = intent.getStringExtra(TrackDownloadManager.EXTRA_DOWNLOAD_KEY) ?: return
        downloadManager.cancelDownload(key)
    }
}

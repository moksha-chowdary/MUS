package com.mus.android.data.model

/**
 * Status values for items in the "MUS — To Download" planning list.
 */
object DownloadStatus {
    const val TO_DOWNLOAD      = "TO_DOWNLOAD"       // queued, not yet actioned
    const val DOWNLOADED       = "DOWNLOADED"        // user marked as downloaded
    const val ADDED_TO_LIBRARY = "ADDED_TO_LIBRARY"  // user imported file into /Muzic/
    const val FAILED           = "FAILED"            // acquisition attempt failed
}

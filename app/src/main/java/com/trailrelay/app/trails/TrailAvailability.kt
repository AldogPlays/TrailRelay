package com.trailrelay.app.trails

import com.trailrelay.app.offline.OfflineDownloads

fun imageryState(downloads: OfflineDownloads, trailId: String): ImageryState {
    if (!downloads.loaded && downloads.loadError == null) return ImageryState.CHECKING
    if (downloads.loadError != null) return ImageryState.FAILED
    val item = downloads.packageFor(trailId)
    return when {
        item?.deleting == true || downloads.isCreating(trailId) -> ImageryState.PREPARING
        item?.complete == true -> ImageryState.COMPLETE
        item?.error != null || downloads.errorFor(trailId) != null -> ImageryState.FAILED
        item?.active == true -> ImageryState.DOWNLOADING
        item != null && item.status == null -> ImageryState.CHECKING
        item != null -> ImageryState.INCOMPLETE
        else -> ImageryState.NONE
    }
}

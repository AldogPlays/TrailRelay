package com.trailrelay.app.trails

import com.trailrelay.app.offline.OfflineDownloads
import com.trailrelay.app.map.MapMode

fun imageryState(downloads: OfflineDownloads, trailId: String, mode: MapMode): ImageryState {
    if (!downloads.loaded && downloads.loadError == null) return ImageryState.CHECKING
    if (downloads.loadError != null) return ImageryState.FAILED
    val item = downloads.packageFor(trailId, mode)
    return when {
        item?.deleting == true || downloads.isCreating(trailId, mode) -> ImageryState.PREPARING
        item?.complete == true -> ImageryState.COMPLETE
        item?.error != null || downloads.errorFor(trailId, mode) != null -> ImageryState.FAILED
        item?.active == true -> ImageryState.DOWNLOADING
        item != null && item.status == null -> ImageryState.CHECKING
        item != null -> ImageryState.INCOMPLETE
        else -> ImageryState.NONE
    }
}

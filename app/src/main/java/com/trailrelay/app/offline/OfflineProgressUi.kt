package com.trailrelay.app.offline

import android.content.Context
import android.text.format.Formatter
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.trailrelay.app.R
import org.maplibre.android.offline.OfflineRegionStatus

enum class OfflineOperationState { NONE, CHECKING, PREPARING, DOWNLOADING, PAUSED, COMPLETE, FAILED, DELETING }

fun offlineOperationState(creating: Boolean, deleting: Boolean, state: OfflineLibraryState?): OfflineOperationState = when {
    deleting -> OfflineOperationState.DELETING
    creating -> OfflineOperationState.PREPARING
    state == null -> OfflineOperationState.NONE
    state == OfflineLibraryState.CHECKING -> OfflineOperationState.CHECKING
    state == OfflineLibraryState.DOWNLOADING -> OfflineOperationState.DOWNLOADING
    state == OfflineLibraryState.INCOMPLETE -> OfflineOperationState.PAUSED
    state == OfflineLibraryState.COMPLETE -> OfflineOperationState.COMPLETE
    else -> OfflineOperationState.FAILED
}

/** Only MapLibre's precise resource total is suitable for a percentage. */
fun preciseOfflinePercent(completed: Long, required: Long, precise: Boolean): Int? =
    if (precise && required > 0) ((completed.coerceIn(0, required) * 100) / required).toInt() else null

fun LinearProgressIndicator.showOfflineProgress(state: OfflineOperationState, status: OfflineRegionStatus?) {
    visibility = if (state == OfflineOperationState.PREPARING || state == OfflineOperationState.DOWNLOADING ||
        state == OfflineOperationState.DELETING) android.view.View.VISIBLE else android.view.View.GONE
    if (visibility == android.view.View.GONE) return
    val percent = status?.let { preciseOfflinePercent(it.completedResourceCount, it.requiredResourceCount,
        it.isRequiredResourceCountPrecise) }
    isIndeterminate = percent == null || state != OfflineOperationState.DOWNLOADING
    if (!isIndeterminate) setProgressCompat(percent!!, true)
}

fun offlineProgressMetrics(context: Context, status: OfflineRegionStatus?): String = status?.let {
    buildString {
        append(context.getString(R.string.offline_tiles_downloaded, it.completedTileCount))
        if (it.completedResourceSize > 0) {
            append(" · ")
            append(context.getString(R.string.offline_bytes_downloaded,
                Formatter.formatFileSize(context, it.completedResourceSize)))
        }
        if (it.isRequiredResourceCountPrecise && it.requiredResourceCount > 0) {
            append("\n")
            append(context.getString(R.string.offline_resources_downloaded,
                it.completedResourceCount, it.requiredResourceCount))
        }
    }
}.orEmpty()

fun OfflineOperationState.label(): Int = when (this) {
    OfflineOperationState.NONE -> R.string.offline_not_downloaded
    OfflineOperationState.CHECKING -> R.string.offline_checking
    OfflineOperationState.PREPARING -> R.string.offline_preparing
    OfflineOperationState.DOWNLOADING -> R.string.offline_downloading
    OfflineOperationState.PAUSED -> R.string.offline_paused
    OfflineOperationState.COMPLETE -> R.string.available_offline
    OfflineOperationState.FAILED -> R.string.offline_failed
    OfflineOperationState.DELETING -> R.string.offline_removing
}

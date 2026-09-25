package com.trailrelay.app.trails

import android.content.res.ColorStateList
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.trailrelay.app.R
import com.trailrelay.app.offline.OfflineLibraryState
import com.trailrelay.app.map.MapMode

enum class RouteChipState { SAVED, NOT_SAVED, CHECKING, MISSING, UNKNOWN, ERROR }
enum class AerialChipState {
    OFFLINE, NOT_OFFLINE, CHECKING, PREPARING, DOWNLOADING, INCOMPLETE, ERROR, UNKNOWN, DELETING;
    companion object
}

fun AerialChipState.Companion.fromImagery(state: ImageryState): AerialChipState = when (state) {
    ImageryState.CHECKING -> AerialChipState.CHECKING
    ImageryState.NONE -> AerialChipState.NOT_OFFLINE
    ImageryState.PREPARING -> AerialChipState.PREPARING
    ImageryState.DOWNLOADING -> AerialChipState.DOWNLOADING
    ImageryState.INCOMPLETE -> AerialChipState.INCOMPLETE
    ImageryState.COMPLETE -> AerialChipState.OFFLINE
    ImageryState.FAILED -> AerialChipState.ERROR
}

fun AerialChipState.Companion.fromLibrary(state: OfflineLibraryState): AerialChipState = when (state) {
    OfflineLibraryState.CHECKING -> AerialChipState.CHECKING
    OfflineLibraryState.DOWNLOADING -> AerialChipState.DOWNLOADING
    OfflineLibraryState.INCOMPLETE -> AerialChipState.INCOMPLETE
    OfflineLibraryState.COMPLETE -> AerialChipState.OFFLINE
    OfflineLibraryState.FAILED -> AerialChipState.ERROR
}

private enum class Tone { COMPLETE, NEUTRAL, ACTIVE, RESUMABLE, ERROR }
private data class Pill(val label: Int, val icon: Int, val tone: Tone)

fun Chip.showRouteStatus(state: RouteChipState) {
    val pill = when (state) {
        RouteChipState.SAVED -> Pill(R.string.chip_route_saved, R.drawable.ic_status_check, Tone.COMPLETE)
        RouteChipState.NOT_SAVED -> Pill(R.string.chip_get_route, R.drawable.ic_status_download, Tone.NEUTRAL)
        RouteChipState.CHECKING -> Pill(R.string.chip_route_checking, R.drawable.ic_status_clock, Tone.ACTIVE)
        RouteChipState.MISSING -> Pill(R.string.chip_route_missing, R.drawable.ic_status_warning, Tone.ERROR)
        RouteChipState.UNKNOWN -> Pill(R.string.chip_route_unknown, R.drawable.ic_status_warning, Tone.ERROR)
        RouteChipState.ERROR -> Pill(R.string.chip_route_error, R.drawable.ic_status_warning, Tone.ERROR)
    }
    show(pill)
}

fun Chip.showAerialStatus(state: AerialChipState, mode: MapMode = MapMode.AERIAL) {
    val pill = when (state) {
        AerialChipState.OFFLINE -> Pill(R.string.chip_aerial_offline, R.drawable.ic_status_check, Tone.COMPLETE)
        AerialChipState.NOT_OFFLINE -> Pill(R.string.chip_not_offline, R.drawable.ic_status_download, Tone.NEUTRAL)
        AerialChipState.CHECKING -> Pill(R.string.chip_aerial_checking, R.drawable.ic_status_clock, Tone.ACTIVE)
        AerialChipState.PREPARING -> Pill(R.string.chip_aerial_preparing, R.drawable.ic_status_clock, Tone.ACTIVE)
        AerialChipState.DOWNLOADING -> Pill(R.string.chip_downloading, R.drawable.ic_status_download, Tone.ACTIVE)
        AerialChipState.INCOMPLETE -> Pill(R.string.chip_resume_map, R.drawable.ic_status_play, Tone.RESUMABLE)
        AerialChipState.ERROR -> Pill(R.string.chip_aerial_error, R.drawable.ic_status_warning, Tone.ERROR)
        AerialChipState.UNKNOWN -> Pill(R.string.chip_unrecognized_map, R.drawable.ic_status_warning, Tone.ERROR)
        AerialChipState.DELETING -> Pill(R.string.chip_deleting_map, R.drawable.ic_status_clock, Tone.ACTIVE)
    }
    show(pill)
    if (state != AerialChipState.UNKNOWN) {
        val status = when (state) {
            AerialChipState.OFFLINE -> context.getString(R.string.map_status_offline)
            AerialChipState.ERROR -> context.getString(R.string.map_status_error)
            else -> context.getString(pill.label)
        }
        text = "${context.getString(mode.label)} · $status"
    }
}

private fun Chip.show(pill: Pill) {
    text = context.getString(pill.label)
    chipIcon = AppCompatResources.getDrawable(context, pill.icon)
    isChipIconVisible = true
    isCheckable = false
    isClickable = false
    isFocusable = false
    val (background, foreground) = when (pill.tone) {
        Tone.COMPLETE -> com.google.android.material.R.attr.colorPrimaryContainer to
            com.google.android.material.R.attr.colorOnPrimaryContainer
        Tone.NEUTRAL -> com.google.android.material.R.attr.colorSurfaceVariant to
            com.google.android.material.R.attr.colorOnSurfaceVariant
        Tone.ACTIVE -> com.google.android.material.R.attr.colorSecondary to
            com.google.android.material.R.attr.colorOnSecondary
        Tone.RESUMABLE -> com.google.android.material.R.attr.colorSurfaceVariant to
            com.google.android.material.R.attr.colorPrimary
        Tone.ERROR -> com.google.android.material.R.attr.colorSurfaceVariant to
            com.google.android.material.R.attr.colorError
    }
    chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(this, background))
    val ink = ColorStateList.valueOf(MaterialColors.getColor(this, foreground))
    chipIconTint = ink
    setTextColor(ink)
}

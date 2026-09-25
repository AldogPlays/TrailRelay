package com.trailrelay.app.map

enum class MapOrientation {
    NORTH_UP, HEADING_UP;

    companion object {
        fun fromPreference(value: String?): MapOrientation =
            entries.firstOrNull { it.name == value } ?: NORTH_UP
    }
}

/** Orientation remains selected when a map gesture suspends following. */
class FollowState(var orientation: MapOrientation, var following: Boolean = true,
    var northResetPending: Boolean = false) {
    fun pan() { following = false; northResetPending = true }
    fun suspendFollow() { following = false; northResetPending = false }
    fun recenter() { following = true; northResetPending = false }

    fun nextOrientationPress(): MapOrientation = when {
        northResetPending -> MapOrientation.NORTH_UP
        orientation == MapOrientation.NORTH_UP -> MapOrientation.HEADING_UP
        else -> MapOrientation.NORTH_UP
    }

    /** Only a user selection of Heading Up resumes suspended follow. */
    fun selectOrientation(next: MapOrientation, userInitiated: Boolean): Boolean {
        orientation = next
        if (userInitiated) northResetPending = false
        if (userInitiated && next == MapOrientation.HEADING_UP && !following) {
            following = true
            return true
        }
        return false
    }
}

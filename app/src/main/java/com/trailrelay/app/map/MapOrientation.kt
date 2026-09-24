package com.trailrelay.app.map

enum class MapOrientation {
    NORTH_UP, HEADING_UP;

    companion object {
        fun fromPreference(value: String?): MapOrientation =
            entries.firstOrNull { it.name == value } ?: NORTH_UP
    }

    /** One-time bearing for map inspection; never changes the camera target or follow state. */
    fun bearingWhilePanned(heading: Float?, headingAgeMillis: Long): Double? = when (this) {
        NORTH_UP -> 0.0
        HEADING_UP -> heading?.takeIf { it.isFinite() && headingAgeMillis in 0..5_000L }
            ?.let { ((it % 360f + 360f) % 360f).toDouble() }
    }
}

/** Orientation remains selected when a map gesture suspends following. */
class FollowState(var orientation: MapOrientation, var following: Boolean = true) {
    fun pan() { following = false }
    fun recenter() { following = true }
}

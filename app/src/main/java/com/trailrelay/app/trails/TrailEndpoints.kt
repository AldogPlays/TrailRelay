package com.trailrelay.app.trails

data class TrailEndpoints(val start: TrackPoint, val end: TrackPoint?)

/** A loop's endpoints are one destination when they are effectively the same place. */
fun GpxTrack.endpoints(): TrailEndpoints? {
    val points = segments.asSequence().flatten().filter {
        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0
    }.toList()
    val start = points.firstOrNull() ?: return null
    val last = points.last()
    return TrailEndpoints(start, last.takeIf { distanceMeters(start, it) > 10.0 })
}

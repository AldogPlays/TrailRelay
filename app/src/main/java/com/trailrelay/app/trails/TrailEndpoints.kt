package com.trailrelay.app.trails

data class TrailEndpoints(val start: TrackPoint, val end: TrackPoint?)

/** A loop's endpoints are one destination when they are effectively the same place. */
fun GpxTrack.endpoints(): TrailEndpoints? {
    val lines = segments.map { segment -> segment.filter {
        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0
    } }.filter { it.size >= 2 }
    val start = lines.firstOrNull()?.firstOrNull() ?: return null
    val last = lines.last().last()
    return TrailEndpoints(start, last.takeIf { distanceMeters(start, it) > 10.0 })
}

package com.trailrelay.app.trails

data class TrailEndpoints(val start: TrackPoint, val end: TrackPoint?)
data class TrailEndpointMarker(val point: TrackPoint, val label: String)

/** A/B describe GPX order only, never a recommended travel direction. */
fun GpxTrack.endpointMarkers(): List<TrailEndpointMarker> {
    val endpoints = endpoints() ?: return emptyList()
    return if (endpoints.end == null) listOf(TrailEndpointMarker(endpoints.start, "A/B")) else
        listOf(TrailEndpointMarker(endpoints.start, "A"), TrailEndpointMarker(endpoints.end, "B"))
}

/** A loop's endpoints are one destination when they are effectively the same place. */
fun GpxTrack.endpoints(): TrailEndpoints? {
    val lines = segments.map { segment -> segment.filter {
        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0
    } }.filter { segment -> segment.size >= 2 && segment.zipWithNext().any { (a, b) -> distanceMeters(a, b) > 0.0 } }
    val start = lines.firstOrNull()?.firstOrNull() ?: return null
    val last = lines.last().last()
    // Only a single continuous segment can be a closed loop. A disconnected GPX can
    // begin and end near the same spot without providing a traversable loop.
    return TrailEndpoints(start, last.takeIf { lines.size != 1 || distanceMeters(start, it) > 10.0 })
}

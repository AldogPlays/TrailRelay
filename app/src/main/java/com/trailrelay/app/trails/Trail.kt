package com.trailrelay.app.trails

import kotlin.math.*

enum class TrailSource { IMPORTED, COMMUNITY }

data class Trail(
    val id: String,
    val name: String,
    val description: String?,
    val source: TrailSource,
    val gpxLocalPath: String,
    val distanceMeters: Double,
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
    val importedAt: Long,
    val remoteId: String? = null,
)

data class TrackPoint(val latitude: Double, val longitude: Double, val elevation: Double? = null)
data class GpxTrack(val name: String?, val description: String?, val segments: List<List<TrackPoint>>) {
    val distanceMeters: Double get() = segments.sumOf { segment ->
        segment.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
    }
}

/** Horizontal great-circle distance; segment gaps and elevation do not add distance. */
fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
        cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

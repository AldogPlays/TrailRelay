package com.trailrelay.app.offline

import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.TrackPoint
import com.trailrelay.app.trails.distanceMeters
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfConstants
import org.maplibre.turf.TurfTransformation
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.tan

/** A union of Turf geodesic circles sampled along each GPX segment.
 * MapLibre Native's geometry tile cover combines overlapping MultiPolygon rings;
 * gaps between GPX segments are never joined. The 1.75 km radius preserves at least
 * about 1.5 km of side context after sampling and the 24-sided circle approximation.
 */
internal object OfflineCorridor {
    const val RADIUS_METERS = 1_750.0
    private const val SAMPLE_METERS = 750.0
    private const val SKIP_NEARBY_METERS = 150.0
    private const val ESTIMATE_RADIUS_METERS = 2_000.0
    private const val MAX_CENTERS = 10_000
    private const val CIRCLE_STEPS = 24

    data class Result(val geometry: MultiPolygon, val estimatedTiles: Long, val samples: Int)

    fun build(track: GpxTrack): Result {
        val points = track.segments.flatten()
        require(points.isNotEmpty()) { "This trail has no track points." }
        OfflineCoverage.padded(CoverageBounds(points.minOf { it.latitude }, points.minOf { it.longitude },
            points.maxOf { it.latitude }, points.maxOf { it.longitude }), RADIUS_METERS)
        val centers = mutableListOf<TrackPoint>()
        track.segments.filter { it.isNotEmpty() }.forEach { segment ->
            var last = segment.first()
            centers.add(last)
            require(centers.size <= MAX_CENTERS) { "This trail is too detailed for an offline map." }
            segment.drop(1).forEachIndexed { index, point ->
                if (index < segment.size - 2 && distanceMeters(last, point) < SKIP_NEARBY_METERS)
                    return@forEachIndexed
                val length = distanceMeters(last, point)
                if (length > 0) {
                    val steps = ceil(length / SAMPLE_METERS).toInt()
                    require(centers.size + steps <= MAX_CENTERS) {
                        "This trail is too large for an offline map."
                    }
                    for (step in 1..steps) {
                        val fraction = step.toDouble() / steps
                        centers.add(TrackPoint(last.latitude + (point.latitude - last.latitude) * fraction,
                            last.longitude + (point.longitude - last.longitude) * fraction))
                    }
                }
                last = point
            }
        }
        val estimate = estimateTiles(centers)
        require(!OfflineCoverage.isTooLarge(estimate)) {
            "This trail area is too large for offline maps in this version."
        }
        val circles = centers.map { center -> TurfTransformation.circle(
            Point.fromLngLat(center.longitude, center.latitude), RADIUS_METERS,
            CIRCLE_STEPS, TurfConstants.UNIT_METRES) }
        return Result(MultiPolygon.fromPolygons(circles), estimate, centers.size)
    }

    /** Union of expanded circle boxes by tile, conservative without counting distant corners. */
    private fun estimateTiles(centers: List<TrackPoint>): Long {
        val tiles = HashSet<Long>()
        for (center in centers) {
            val bounds = OfflineCoverage.padded(CoverageBounds(center.latitude, center.longitude,
                center.latitude, center.longitude), ESTIMATE_RADIUS_METERS)
            for (zoom in OfflineCoverage.MIN_ZOOM..OfflineCoverage.MAX_ZOOM) {
                val size = 1L shl zoom
                fun x(lon: Double) = floor((lon + 180) / 360 * size).toLong().coerceIn(0, size - 1)
                fun y(lat: Double): Long {
                    val radians = Math.toRadians(lat)
                    return floor((1 - asinh(tan(radians)) / PI) / 2 * size).toLong().coerceIn(0, size - 1)
                }
                for (tileX in x(bounds.west)..x(bounds.east))
                    for (tileY in y(bounds.north)..y(bounds.south)) {
                        tiles.add((zoom.toLong() shl 40) or (tileX shl 20) or tileY)
                        require(!OfflineCoverage.isTooLarge(tiles.size.toLong())) {
                            "This trail area is too large for offline maps in this version."
                        }
                    }
            }
        }
        return tiles.size.toLong()
    }
}

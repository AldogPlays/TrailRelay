package com.trailrelay.app.offline

import kotlin.math.*

data class CoverageBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** Pure geographic policy, independent of MapLibre and Android. */
object OfflineCoverage {
    const val MIN_ZOOM = 12
    const val MAX_ZOOM = 16
    const val TILE_LIMIT = 5_500L
    private const val MERCATOR_LIMIT = 85.0511287798066

    fun padded(bounds: CoverageBounds): CoverageBounds {
        require(listOf(bounds.south, bounds.west, bounds.north, bounds.east).all { it.isFinite() } &&
            bounds.south >= -MERCATOR_LIMIT && bounds.north <= MERCATOR_LIMIT &&
            bounds.south <= bounds.north && bounds.west >= -180 && bounds.east <= 180 &&
            bounds.west <= bounds.east) { "This trail has invalid map bounds." }
        val latitudePadding = Math.toDegrees(1_500.0 / 6_371_008.8)
        val south = bounds.south - latitudePadding
        val north = bounds.north + latitudePadding
        require(south >= -MERCATOR_LIMIT && north <= MERCATOR_LIMIT) {
            "This trail is outside the supported aerial map area."
        }
        // Use the most poleward edge so the east/west padding is at least 1.5 km.
        val longitudePadding = latitudePadding / cos(Math.toRadians(max(abs(south), abs(north))))
        require(bounds.west - longitudePadding >= -180 && bounds.east + longitudePadding <= 180) {
            "Trails crossing the date line are not supported for offline maps yet."
        }
        return CoverageBounds(south, bounds.west - longitudePadding, north, bounds.east + longitudePadding)
    }

    /** Inclusive edge tiles deliberately overestimate exact tile-boundary cases. One raster source. */
    fun estimateTiles(bounds: CoverageBounds, minZoom: Int = MIN_ZOOM, maxZoom: Int = MAX_ZOOM): Long {
        require(minZoom in 0..22 && maxZoom in minZoom..22)
        require(listOf(bounds.south, bounds.west, bounds.north, bounds.east).all { it.isFinite() } &&
            bounds.south >= -MERCATOR_LIMIT && bounds.north <= MERCATOR_LIMIT &&
            bounds.south <= bounds.north && bounds.west >= -180 && bounds.east <= 180 && bounds.west <= bounds.east)
        return (minZoom..maxZoom).sumOf { zoom ->
            val size = 1L shl zoom
            fun x(lon: Double) = floor((lon + 180) / 360 * size).toLong().coerceIn(0, size - 1)
            fun y(lat: Double): Long {
                val radians = Math.toRadians(lat)
                return floor((1 - asinh(tan(radians)) / PI) / 2 * size).toLong().coerceIn(0, size - 1)
            }
            (x(bounds.east) - x(bounds.west) + 1) * (y(bounds.south) - y(bounds.north) + 1)
        }
    }

    fun isTooLarge(tileCount: Long) = tileCount >= TILE_LIMIT
}

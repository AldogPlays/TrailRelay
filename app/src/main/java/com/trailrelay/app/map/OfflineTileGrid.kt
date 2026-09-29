package com.trailrelay.app.map

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

data class GeoPoint(val lon: Double, val lat: Double)
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun intersects(other: GeoBox) = south <= other.north && north >= other.south &&
        west <= other.east && east >= other.west
    fun contains(point: GeoPoint) = point.lat in south..north && point.lon in west..east
}
data class TileCoordinate(val z: Int, val x: Int, val y: Int)
data class CoverageTile(val coordinate: TileCoordinate, val complete: Boolean)

sealed interface CoverageShape {
    val box: GeoBox
    fun intersects(tile: GeoBox): Boolean
}

data class BoxShape(override val box: GeoBox) : CoverageShape {
    override fun intersects(tile: GeoBox) = box.intersects(tile)
}

/** Polygon rings are retained only for the debug setting; bbox prefilter avoids edge tests
 * against the many corridor samples that are nowhere near the current viewport. */
class PolygonShape(val rings: List<List<GeoPoint>>) : CoverageShape {
    override val box: GeoBox = rings.asSequence().flatten().let { points ->
        var south = Double.POSITIVE_INFINITY; var west = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY; var east = Double.NEGATIVE_INFINITY
        points.forEach { point ->
            south = min(south, point.lat); west = min(west, point.lon)
            north = max(north, point.lat); east = max(east, point.lon)
        }
        GeoBox(south, west, north, east)
    }

    override fun intersects(tile: GeoBox): Boolean {
        if (!box.intersects(tile)) return false
        val corners = listOf(GeoPoint(tile.west, tile.south), GeoPoint(tile.east, tile.south),
            GeoPoint(tile.east, tile.north), GeoPoint(tile.west, tile.north))
        if (corners.any { inside(it) }) return true
        if (rings.first().any(tile::contains)) return true
        val edges = corners.indices.map { corners[it] to corners[(it + 1) % 4] }
        return rings.any { ring -> ring.indices.any { index ->
            val a = ring[index]; val b = ring[(index + 1) % ring.size]
            edges.any { (c, d) -> segmentsIntersect(a, b, c, d) }
        } }
    }

    private fun inside(point: GeoPoint): Boolean = pointInRing(point, rings.first()) &&
        rings.drop(1).none { pointInRing(point, it) }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        var previous = ring.last()
        for (current in ring) {
            if ((current.lat > point.lat) != (previous.lat > point.lat) &&
                point.lon < (previous.lon - current.lon) * (point.lat - current.lat) /
                    (previous.lat - current.lat) + current.lon) inside = !inside
            previous = current
        }
        return inside
    }

    private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        fun cross(p: GeoPoint, q: GeoPoint, r: GeoPoint) =
            (q.lon - p.lon) * (r.lat - p.lat) - (q.lat - p.lat) * (r.lon - p.lon)
        val abC = cross(a, b, c); val abD = cross(a, b, d)
        val cdA = cross(c, d, a); val cdB = cross(c, d, b)
        return abC * abD <= 0 && cdA * cdB <= 0 &&
            max(a.lon, b.lon) >= min(c.lon, d.lon) && max(c.lon, d.lon) >= min(a.lon, b.lon) &&
            max(a.lat, b.lat) >= min(c.lat, d.lat) && max(c.lat, d.lat) >= min(a.lat, b.lat)
    }
}

data class PreparedCoverageRegion(val shapes: List<CoverageShape>, val minZoom: Int,
                                  val maxZoom: Int, val complete: Boolean)

/** XYZ/Web Mercator tile debug grid. One tile of viewport padding, hard cap BEFORE intersection.
 * MapLibre's camera world uses 512px tiles: 256px raster sources select one higher tile z.
 * Source z = floor(camera zoom + log2(512/256)), capped to package/source zoom 12–16.
 */
object OfflineTileGrid {
    const val MAX_DEBUG_TILES = 256
    const val MAX_MERCATOR_LAT = 85.0511287798066
    fun zoom(cameraZoom: Double, minZoom: Int = 12, maxZoom: Int = 16): Int =
        floor(cameraZoom + 1.0).toInt().coerceIn(minZoom, maxZoom)
    fun x(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z))
        .toInt().coerceIn(0, (1 shl z) - 1)
    fun y(lat: Double, z: Int): Int {
        val radians = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
        return floor((1 - asinh(tan(radians)) / PI) / 2 * (1 shl z))
            .toInt().coerceIn(0, (1 shl z) - 1)
    }
    fun bounds(tile: TileCoordinate): GeoBox {
        val size = (1 shl tile.z).toDouble()
        fun lat(y: Int) = Math.toDegrees(kotlin.math.atan(sinh(PI * (1 - 2 * y / size))))
        return GeoBox(lat(tile.y + 1), tile.x / size * 360 - 180,
            lat(tile.y), (tile.x + 1) / size * 360 - 180)
    }

    /** Suppress absurd candidate ranges rather than allocate/freeze at low zoom. */
    fun candidates(view: GeoBox, z: Int, padding: Int = 1, cap: Int = MAX_DEBUG_TILES): List<TileCoordinate> {
        if (!listOf(view.south, view.west, view.north, view.east).all(Double::isFinite) ||
            view.south > view.north || view.south > MAX_MERCATOR_LAT || view.north < -MAX_MERCATOR_LAT) return emptyList()
        val size = 1 shl z
        val top = (y(view.north, z) - padding).coerceAtLeast(0)
        val bottom = (y(view.south, z) + padding).coerceAtMost(size - 1)
        val longitudeRanges = if (view.west <= view.east) listOf(view.west to view.east)
            else listOf(view.west to 180.0, -180.0 to view.east)
        val ranges = longitudeRanges.map { (west, east) ->
            (x(west, z) - padding).coerceAtLeast(0)..(x(east, z) + padding).coerceAtMost(size - 1)
        }
        val count = (bottom - top + 1).toLong() * ranges.sumOf { (it.last - it.first + 1).toLong() }
        if (count > cap) return emptyList()
        return buildList {
            for (range in ranges) for (column in range) for (row in top..bottom)
                add(TileCoordinate(z, column, row))
        }.distinct()
    }

    fun covered(view: GeoBox, cameraZoom: Double, regions: List<PreparedCoverageRegion>,
                cap: Int = MAX_DEBUG_TILES): List<CoverageTile> {
        if (regions.isEmpty()) return emptyList()
        val z = zoom(cameraZoom)
        val eligible = regions.filter { z in it.minZoom..it.maxZoom }
        if (eligible.isEmpty()) return emptyList()
        val candidates = candidates(view, z, cap = cap)
        if (candidates.isEmpty()) return emptyList()
        val candidateBoxes = candidates.map(::bounds)
        val broad = GeoBox(candidateBoxes.minOf { it.south }, candidateBoxes.minOf { it.west },
            candidateBoxes.maxOf { it.north }, candidateBoxes.maxOf { it.east })
        val visibleShapes = eligible.map { region -> region to region.shapes.filter { it.box.intersects(broad) } }
        return candidates.mapNotNull { tile ->
            val box = bounds(tile)
            val matching = visibleShapes.filter { (_, shapes) -> shapes.any { it.intersects(box) } }
            if (matching.isEmpty()) null else CoverageTile(tile, matching.any { it.first.complete })
        }
    }
}

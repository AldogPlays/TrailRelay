package com.trailrelay.app.map

import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Public region definitions become a bounded tile grid. Corridor circles are never rendered. */
object OfflineCoverageOverlay {
    data class Region(val definition: OfflineRegionDefinition, val complete: Boolean)
    fun prepare(regions: List<Region>): List<PreparedCoverageRegion> = regions.mapNotNull { region ->
        val definition = region.definition
        val shapes = when (definition) {
            is OfflineGeometryRegionDefinition -> definition.geometry?.let(::shapes).orEmpty()
            is OfflineTilePyramidRegionDefinition -> definition.bounds?.let { bounds ->
                listOf(BoxShape(GeoBox(bounds.getLatSouth(), bounds.getLonWest(),
                    bounds.getLatNorth(), bounds.getLonEast())))
            }.orEmpty()
            else -> emptyList()
        }
        if (shapes.isEmpty()) null else PreparedCoverageRegion(shapes,
            definition.minZoom.toInt(), definition.maxZoom.toInt(), region.complete)
    }

    private fun shapes(geometry: Geometry): List<CoverageShape> {
        val polygons = when (geometry) {
            is MultiPolygon -> geometry.coordinates()
            is Polygon -> listOf(geometry.coordinates())
            else -> emptyList()
        }
        return polygons.mapNotNull { polygon ->
            val rings = polygon.map { ring -> ring.map { GeoPoint(it.longitude(), it.latitude()) } }
                .filter { it.size >= 3 }
            if (rings.isEmpty()) null else PolygonShape(rings)
        }
    }

    fun geoJson(tiles: List<CoverageTile>): String = FeatureCollection.fromFeatures(tiles.map { tile ->
        val box = OfflineTileGrid.bounds(tile.coordinate)
        Feature.fromGeometry(Polygon.fromLngLats(listOf(listOf(
            Point.fromLngLat(box.west, box.south), Point.fromLngLat(box.east, box.south),
            Point.fromLngLat(box.east, box.north), Point.fromLngLat(box.west, box.north),
            Point.fromLngLat(box.west, box.south))))).apply {
            addBooleanProperty("complete", tile.complete)
        }
    }).toJson()
}

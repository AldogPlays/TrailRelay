package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class OfflineCoverageOverlayTest {
    private val view = GeoBox(39.99, -105.01, 40.02, -104.98)
    @Test fun legacyBoundsSelectOnlyIntersectingSquares() {
        val legacy = OfflineTilePyramidRegionDefinition("asset://usgs_imagery.json",
            LatLngBounds.from(40.01, -104.99, 40.0, -105.0), 12.0, 16.0, 1f)
        val prepared = OfflineCoverageOverlay.prepare(listOf(OfflineCoverageOverlay.Region(legacy, true)))
        val tiles = OfflineTileGrid.covered(view, 15.4, prepared)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.complete && it.coordinate.z == 16 })
        val footprint = prepared.single().shapes.single().box
        assertTrue(tiles.all { OfflineTileGrid.bounds(it.coordinate).intersects(footprint) })
        val features = FeatureCollection.fromJson(OfflineCoverageOverlay.geoJson(tiles)).features()!!
        assertEquals(tiles.size, features.size)
        assertTrue(features.all { it.getBooleanProperty("complete") &&
            (it.geometry() as Polygon).coordinates().single().size == 5 })
    }

    @Test fun geometryDefinitionUsesIntersectionButNeverRendersCorridorPolygons() {
        val ring = listOf(Point.fromLngLat(-105.002, 40.0), Point.fromLngLat(-104.995, 40.0),
            Point.fromLngLat(-104.995, 40.008), Point.fromLngLat(-105.002, 40.008),
            Point.fromLngLat(-105.002, 40.0))
        val corridor = MultiPolygon.fromPolygons(listOf(Polygon.fromLngLats(listOf(ring))))
        val definition = OfflineGeometryRegionDefinition("asset://usgs_imagery.json", corridor,
            12.0, 16.0, 1f)
        val prepared = OfflineCoverageOverlay.prepare(listOf(OfflineCoverageOverlay.Region(definition, false)))
        val tiles = OfflineTileGrid.covered(view, 16.0, prepared)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { !it.complete })
        val features = FeatureCollection.fromJson(OfflineCoverageOverlay.geoJson(tiles)).features()!!
        assertTrue(features.all { !it.getBooleanProperty("complete") && it.geometry() is Polygon })
        assertTrue(features.none { it.geometry() == corridor })
    }
}

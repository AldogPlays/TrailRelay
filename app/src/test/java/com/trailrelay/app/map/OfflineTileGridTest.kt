package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test

class OfflineTileGridTest {
    @Test fun worldMercatorCoordinatesAndBounds() {
        assertEquals(0, OfflineTileGrid.x(-180.0, 0))
        assertEquals(0, OfflineTileGrid.y(0.0, 0))
        assertEquals(1, OfflineTileGrid.x(0.0, 1))
        assertEquals(1, OfflineTileGrid.y(0.0, 1))
        val box = OfflineTileGrid.bounds(TileCoordinate(1, 1, 1))
        assertEquals(-OfflineTileGrid.MAX_MERCATOR_LAT, box.south, 1e-8)
        assertEquals(0.0, box.west, 1e-10)
        assertEquals(180.0, box.east, 1e-10)
        assertEquals(0.0, box.north, 1e-10)
    }

    @Test fun fractionalZoomUsesFloorThenPackageSourceClamp() {
        assertEquals(15, OfflineTileGrid.zoom(14.99))
        assertEquals(16, OfflineTileGrid.zoom(15.0))
        assertEquals(16, OfflineTileGrid.zoom(19.0))
        assertEquals(12, OfflineTileGrid.zoom(3.0))
    }

    @Test fun candidatesAreViewportOnlyAndSafetyCapSuppressesHugeViews() {
        val view = GeoBox(40.0, -105.01, 40.01, -105.0)
        val candidates = OfflineTileGrid.candidates(view, 16)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.size <= OfflineTileGrid.MAX_DEBUG_TILES)
        assertTrue(candidates.all { it.z == 16 })
        assertTrue(OfflineTileGrid.candidates(GeoBox(-80.0, -180.0, 80.0, 180.0), 12).isEmpty())
        assertTrue(OfflineTileGrid.candidates(view, 16, cap = 1).isEmpty())
    }

    @Test fun bboxAndPolygonIntersectionExcludeOutsideTiles() {
        val tile = TileCoordinate(16, OfflineTileGrid.x(-105.0, 16), OfflineTileGrid.y(40.0, 16))
        val inside = OfflineTileGrid.bounds(tile)
        val far = GeoBox(41.0, -106.0, 41.01, -105.99)
        assertTrue(BoxShape(inside).intersects(inside))
        assertFalse(BoxShape(far).intersects(inside))
        val ring = listOf(GeoPoint(inside.west + .001, inside.south + .001),
            GeoPoint(inside.east - .001, inside.south + .001),
            GeoPoint(inside.east - .001, inside.north - .001),
            GeoPoint(inside.west + .001, inside.north - .001))
        assertTrue(PolygonShape(listOf(ring)).intersects(inside))
        assertFalse(PolygonShape(listOf(ring)).intersects(far))
        val view = GeoBox(inside.south, inside.west, inside.north, inside.east)
        val covered = OfflineTileGrid.covered(view, 16.0,
            listOf(PreparedCoverageRegion(listOf(PolygonShape(listOf(ring))), 12, 16, true)))
        assertTrue(covered.isNotEmpty())
        assertTrue(covered.all { OfflineTileGrid.bounds(it.coordinate).intersects(PolygonShape(listOf(ring)).box) })
    }

    @Test fun packageZoomAndCompleteStatusAreTruthful() {
        val view = GeoBox(39.99, -105.01, 40.01, -104.99)
        val shape = BoxShape(view)
        val incomplete = PreparedCoverageRegion(listOf(shape), 14, 16, false)
        assertTrue(OfflineTileGrid.covered(view, 12.0, listOf(incomplete)).isEmpty())
        val requested = OfflineTileGrid.covered(view, 14.0, listOf(incomplete))
        assertTrue(requested.isNotEmpty() && requested.all { !it.complete })
        val complete = PreparedCoverageRegion(listOf(shape), 14, 16, true)
        val both = OfflineTileGrid.covered(view, 14.0, listOf(incomplete, complete))
        assertEquals(requested.map { it.coordinate }, both.map { it.coordinate })
        assertTrue(both.all { it.complete })
    }
}

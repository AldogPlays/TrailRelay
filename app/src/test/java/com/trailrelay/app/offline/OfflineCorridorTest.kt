package com.trailrelay.app.offline

import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfJoins
import org.maplibre.turf.TurfMeasurement
import org.maplibre.turf.TurfConstants

class OfflineCorridorTest {
    @Test fun endpointsKeepUsefulOffRouteContext() {
        val start = Point.fromLngLat(-110.0, 40.0)
        val track = GpxTrack(null, null, listOf(listOf(
            TrackPoint(40.0, -110.0), TrackPoint(40.02, -110.0))))
        val corridor = OfflineCorridor.build(track)
        val inside = TurfMeasurement.destination(start, 1_500.0, 270.0, TurfConstants.UNIT_METRES)
        val outside = TurfMeasurement.destination(start, 2_100.0, 270.0, TurfConstants.UNIT_METRES)
        assertTrue(TurfJoins.inside(inside, corridor.geometry))
        assertFalse(TurfJoins.inside(outside, corridor.geometry))
    }

    @Test fun diagonalTrailUsesFarFewerTilesThanItsBoundingRectangle() {
        val track = GpxTrack(null, null, listOf(listOf(
            TrackPoint(39.8, -110.2), TrackPoint(40.2, -109.8))))
        val corridor = OfflineCorridor.build(track)
        val rectangle = OfflineCoverage.estimateTiles(OfflineCoverage.padded(
            CoverageBounds(39.8, -110.2, 40.2, -109.8)))
        assertTrue(corridor.estimatedTiles < rectangle / 2)
        assertTrue(corridor.samples > 2)
        assertEquals(corridor.samples, corridor.geometry.polygons().size)
    }

    @Test fun segmentGapsDoNotBecomeDownloadedCorridors() {
        val track = GpxTrack(null, null, listOf(
            listOf(TrackPoint(40.0, -110.0), TrackPoint(40.001, -110.0)),
            listOf(TrackPoint(40.0, -109.7), TrackPoint(40.001, -109.7))))
        val corridor = OfflineCorridor.build(track)
        assertFalse(TurfJoins.inside(Point.fromLngLat(-109.85, 40.0), corridor.geometry))
        assertTrue(TurfJoins.inside(Point.fromLngLat(-110.0, 40.0), corridor.geometry))
        assertTrue(TurfJoins.inside(Point.fromLngLat(-109.7, 40.001), corridor.geometry))
    }

    @Test fun windingLoopDoesNotFillItsEmptyCenter() {
        val track = GpxTrack(null, null, listOf(listOf(
            TrackPoint(40.0, -110.0), TrackPoint(40.0, -109.9),
            TrackPoint(40.1, -109.9), TrackPoint(40.1, -110.0),
            TrackPoint(40.0, -110.0))))
        val corridor = OfflineCorridor.build(track)
        assertFalse(TurfJoins.inside(Point.fromLngLat(-109.95, 40.05), corridor.geometry))
    }
}

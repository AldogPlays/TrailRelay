package com.trailrelay.app.trails

import org.junit.Assert.*
import org.junit.Test

class RoutePositionTest {
    private val a = TrackPoint(0.0, 0.0)
    private val b = TrackPoint(0.0, 0.02)
    private fun route(vararg segments: List<TrackPoint>) = PreparedRoute(GpxTrack(null, null, segments.toList()))

    @Test fun pointOnLineHalfwayHasBothEndpointDistances() {
        val p = route(listOf(a, b)).nearest(TrackPoint(0.0, 0.01))!!
        assertEquals(0.0, p.fromRouteMeters, 0.001)
        assertEquals(0.5, p.edgeFraction, 0.000001)
        assertEquals(distanceMeters(a, b) / 2, p.toAMeters!!, 0.001)
        assertEquals(p.toAMeters!!, p.toBMeters!!, 0.001)
        assertEquals(p.toAMeters!!, p.cumulativeMeters, 0.001)
    }

    @Test fun perpendicularProjectionFallsBetweenVertices() {
        val user = TrackPoint(0.001, 0.01)
        val p = route(listOf(a, b)).nearest(user)!!
        assertEquals(0.0, p.coordinate.latitude, 0.000001)
        assertEquals(0.01, p.coordinate.longitude, 0.000001)
        assertEquals(distanceMeters(user, TrackPoint(0.0, 0.01)), p.fromRouteMeters, 0.001)
    }

    @Test fun beyondLineProjectsToEndpoint() {
        val p = route(listOf(a, b)).nearest(TrackPoint(0.0, 0.03))!!
        assertEquals(b.longitude, p.coordinate.longitude, 0.000001)
        assertEquals(0.0, p.toBMeters!!, 0.001)
        assertEquals(distanceMeters(a, b), p.toAMeters!!, 0.001)
        assertEquals(0.0, route(listOf(a, b)).nearest(TrackPoint(0.0, -0.01))!!.toAMeters!!, 0.001)
    }

    @Test fun curvedPolylineUsesOrderedEdges() {
        val c = TrackPoint(0.02, 0.02)
        val p = route(listOf(a, b, c)).nearest(TrackPoint(0.01, 0.021))!!
        assertEquals(1, p.edgeIndex)
        assertEquals(distanceMeters(a, b) + distanceMeters(b, c) / 2, p.toAMeters!!, 0.1)
        assertEquals(distanceMeters(b, c) / 2, p.toBMeters!!, 0.1)
    }

    @Test fun disconnectedSegmentsDoNotFabricateDistancesOrGapProjection() {
        val c = TrackPoint(0.0, 0.04)
        val d = TrackPoint(0.0, 0.06)
        val r = route(listOf(a, b), listOf(c, d))
        val first = r.nearest(TrackPoint(0.0, 0.01))!!
        assertNotNull(first.toAMeters)
        assertNull(first.toBMeters)
        val second = r.nearest(TrackPoint(0.0, 0.05))!!
        assertEquals(1, second.segmentIndex)
        assertNull(second.toAMeters)
        assertEquals(distanceMeters(c, d) / 2, second.toBMeters!!, 0.001)
        assertEquals(distanceMeters(a, b) + second.segmentMeters, second.cumulativeMeters, 0.001)
        assertTrue(r.nearest(TrackPoint(0.0, 0.03))!!.fromRouteMeters > 1100)
    }

    @Test fun middleSegmentCannotReachEitherGlobalEndpoint() {
        val p = route(listOf(a, b), listOf(TrackPoint(1.0, 0.0), TrackPoint(1.0, 0.02)),
            listOf(TrackPoint(2.0, 0.0), TrackPoint(2.0, 0.02))).nearest(TrackPoint(1.0, 0.01))!!
        assertNull(p.toAMeters)
        assertNull(p.toBMeters)
    }

    @Test fun loopRetainsBothOrderedDistancesToSharedEndpoint() {
        val c = TrackPoint(0.02, 0.02)
        val track = GpxTrack(null, null, listOf(listOf(a, b, c, a)))
        val p = PreparedRoute(track).nearest(b)!!
        assertEquals(track.distanceMeters, p.toAMeters!! + p.toBMeters!!, 0.001)
        assertNull(track.endpoints()!!.end)
    }

    @Test fun ignoresIsolatedAndDegenerateSegments() {
        val isolated = TrackPoint(0.001, 0.01)
        val p = route(listOf(isolated), listOf(isolated, isolated), listOf(a, a, b), emptyList())
            .nearest(isolated)!!
        assertEquals(2, p.segmentIndex)
        assertTrue(p.fromRouteMeters > 100)
        assertNotNull(p.toAMeters)
        assertNotNull(p.toBMeters)
        assertNull(route(listOf(a), listOf(b, b)).nearest(a))
    }

    @Test fun datelineUsesShortArc() {
        val p = route(listOf(TrackPoint(0.0, 179.99), TrackPoint(0.0, -179.99)))
            .nearest(TrackPoint(0.001, 180.0))!!
        assertEquals(0.5, p.edgeFraction, 0.000001)
        assertTrue(p.fromRouteMeters < 112)
    }

    @Test fun invalidPointDoesNotCreateAnEdgeOrConnectivityAcrossIt() {
        val c = TrackPoint(0.0, 0.04)
        val d = TrackPoint(0.0, 0.06)
        val r = route(listOf(a, b, TrackPoint(Double.NaN, 0.0), c, d))
        val p = r.nearest(TrackPoint(0.0, 0.03))!!
        assertTrue(p.fromRouteMeters > 1100)
        assertNull(p.toAMeters)
        assertNull(p.toBMeters)
        assertNull(r.nearest(TrackPoint(91.0, 0.0)))
    }

    @Test fun poorStaleMissingAccuracyIsUnavailable() {
        assertTrue(TrailPositionQuality.usable(15_000, 25f))
        assertFalse(TrailPositionQuality.usable(15_001, 10f))
        assertFalse(TrailPositionQuality.usable(-1, 10f))
        assertFalse(TrailPositionQuality.usable(0, 26f))
        assertFalse(TrailPositionQuality.usable(0, Float.NaN))
        assertFalse(TrailPositionQuality.usable(0, 0f))
        assertTrue(TrailPositionQuality.nearTrail(20.0, 25f))
        assertEquals(100, TrailPositionQuality.roundedFeet(30.0, 25f))
    }
}

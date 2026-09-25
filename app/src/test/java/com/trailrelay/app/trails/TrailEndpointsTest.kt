package com.trailrelay.app.trails

import org.junit.Assert.*
import org.junit.Test

class TrailEndpointsTest {
    @Test fun takesFirstAndLastValidPointsAcrossSegments() {
        val bad = TrackPoint(Double.NaN, 0.0)
        val start = TrackPoint(40.0, -105.0)
        val end = TrackPoint(41.0, -106.0)
        val track = GpxTrack(null, null, listOf(listOf(bad, start, TrackPoint(40.1, -105.1)), emptyList(),
            listOf(TrackPoint(40.5, -105.5), end, TrackPoint(91.0, 0.0))))
        assertEquals(TrailEndpoints(start, end), track.endpoints())
    }

    @Test fun loopHasOneDestination() {
        val start = TrackPoint(40.0, -105.0)
        val nearStart = TrackPoint(40.00005, -105.0)
        val track = GpxTrack(null, null, listOf(listOf(start, TrackPoint(40.1, -105.1), nearStart)))
        assertEquals(TrailEndpoints(start, null), track.endpoints())
        assertNull(GpxTrack(null, null, listOf(emptyList())).endpoints())
    }

    @Test fun nearbyButDistinctEndpointsStillOfferBoth() {
        val start = TrackPoint(40.0, -105.0)
        val end = TrackPoint(40.00014, -105.0)
        assertEquals(TrailEndpoints(start, end),
            GpxTrack(null, null, listOf(listOf(start, end))).endpoints())
    }

    @Test fun isolatedPointsBeforeAndAfterLinesAreNotDestinations() {
        val start = TrackPoint(40.0, -105.0)
        val end = TrackPoint(41.0, -106.0)
        val track = GpxTrack(null, null, listOf(
            listOf(TrackPoint(39.0, -104.0)),
            listOf(start, TrackPoint(40.1, -105.1)),
            listOf(TrackPoint(40.5, -105.5), end),
            listOf(TrackPoint(42.0, -107.0))))
        assertEquals(TrailEndpoints(start, end), track.endpoints())
    }

    @Test fun onlyIsolatedPointsHaveNoNavigationEndpoints() {
        assertNull(GpxTrack(null, null, listOf(
            listOf(TrackPoint(40.0, -105.0)), emptyList(),
            listOf(TrackPoint(41.0, -106.0)))).endpoints())
    }
}

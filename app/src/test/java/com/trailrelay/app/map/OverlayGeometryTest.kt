package com.trailrelay.app.map

import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString

class OverlayGeometryTest {
    private val track = GpxTrack(null, null, listOf(
        listOf(TrackPoint(0.0, 0.0), TrackPoint(0.0, 0.01)),
        listOf(TrackPoint(1.0, 1.0), TrackPoint(1.0, 1.01))))

    @Test fun retainedGeometrySurvivesSelectionAndStyleRestorationWithoutRebuilding() {
        val prep = OverlayPreparation()
        val library = mapOf("a" to track)
        val browse = prep.prepare(library, null)
        val selected = prep.prepare(library, track, false)
        assertTrue(FeatureCollection.fromJson(selected.browse).features()!!.isEmpty())
        assertEquals(2, MultiLineString.fromJson(selected.selected.lines).coordinates().size)
        assertEquals(2, FeatureCollection.fromJson(selected.selected.endpoints).features()!!.size)
        val styleReload = prep.prepare(library, track, false)
        assertSame(selected.selected, styleReload.selected)
        val cleared = prep.prepare(library, null)
        assertSame(browse.browse, cleared.browse)
        assertTrue(FeatureCollection.fromJson(cleared.selected.endpoints).features()!!.isEmpty())
    }

    @Test fun removedRoutesDoNotRemainInPreparedLibrary() {
        val prep = OverlayPreparation()
        prep.prepare(mapOf("a" to track), null)
        assertTrue(FeatureCollection.fromJson(prep.prepare(emptyMap(), null).browse).features()!!.isEmpty())
    }

    @Test fun growingLibraryOnlyEncodesAddedOrReplacedRoutes() {
        val prep = OverlayPreparation()
        prep.prepare(mapOf("a" to track), null)
        assertEquals(1, prep.encodedRoutes)
        val expanded = prep.prepare(mapOf("a" to track, "b" to track), null)
        assertEquals(1, prep.encodedRoutes)
        assertEquals(4, FeatureCollection.fromJson(expanded.browse).features()!!.size)
        prep.prepare(mapOf("a" to track, "b" to track), null)
        assertEquals(0, prep.encodedRoutes)
        val replacement = GpxTrack(null, null, listOf(track.segments.first()))
        val replaced = prep.prepare(mapOf("a" to replacement, "b" to track), null)
        assertEquals(1, prep.encodedRoutes)
        assertEquals(3, FeatureCollection.fromJson(replaced.browse).features()!!.size)
        val removed = prep.prepare(mapOf("b" to track), null)
        assertEquals(0, prep.encodedRoutes)
        assertTrue(FeatureCollection.fromJson(removed.browse).features()!!.all {
            it.getStringProperty(TrailOverlay.TRAIL_ID) == "b"
        })
    }

    @Test fun cachedFeatureAssemblyPreservesEscapingSegmentsAndCoordinates() {
        val id = "trail\"\\\nname"
        val mixed = GpxTrack(null, null, listOf(emptyList(), listOf(TrackPoint(2.0, 2.0))) + track.segments)
        val data = OverlayPreparation().prepare(mapOf("isolated" to GpxTrack(null, null,
            listOf(listOf(TrackPoint(3.0, 3.0)))), id to mixed), null)
        val features = FeatureCollection.fromJson(data.browse).features()!!
        assertEquals(2, features.size)
        features.forEachIndexed { index, feature ->
            assertEquals(id, feature.getStringProperty(TrailOverlay.TRAIL_ID))
            val line = feature.geometry() as org.maplibre.geojson.LineString
            assertEquals(2, line.coordinates().size)
            assertEquals(track.segments[index][1].longitude, line.coordinates()[1].longitude(), 0.0)
            assertEquals(track.segments[index][1].latitude, line.coordinates()[1].latitude(), 0.0)
        }
    }
}

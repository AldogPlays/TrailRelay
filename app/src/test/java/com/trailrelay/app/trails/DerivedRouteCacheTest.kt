package com.trailrelay.app.trails

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DerivedRouteCacheTest {
    private val trail = Trail("a", "Trail", null, TrailSource.IMPORTED, "a.gpx",
        100.0, 0.0, 0.0, 1.0, 1.0, 0L)
    private val track = GpxTrack("Name", "Description λ", listOf(
        listOf(TrackPoint(9.0, 9.0)),
        listOf(TrackPoint(0.0, 0.0, 12.3), TrackPoint(0.0, 0.01)),
        listOf(TrackPoint(1.0, 1.0), TrackPoint(1.0, 1.01, -2.0))))
    private fun withRoot(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("derived-route-test").toFile()
        try { test(root, File(root, trail.gpxLocalPath).apply { writeText("canonical") }) }
        finally { root.deleteRecursively() }
    }

    @Test fun missingCacheParsesOnceAndNewInstanceHits() = withRoot { root, _ ->
        var parses = 0
        val events = mutableListOf<String>()
        val first = DerivedRouteCache(root, report = { phase, _, _ -> events.add(phase) })
        assertEquals(track, first.load(trail) { parses++; track })
        assertEquals(1, parses)
        assertTrue(events.containsAll(listOf("route_cache_miss", "route_cache_write")))
        val second = DerivedRouteCache(root, report = { phase, _, _ -> events.add(phase) })
        assertEquals(track, second.load(trail) { error("Must not parse canonical GPX on hit") })
        assertTrue(events.containsAll(listOf("route_cache_hit", "route_cache_read")))
        assertEquals("hits=1 misses=0 reparsed=0 scope=activity", second.summary())
    }

    @Test fun newMemoryCacheRestoresDiskGeometryWithoutParsing() = withRoot { root, _ ->
        SavedGeometryCache(root, DerivedRouteCache(root)) { _, _ -> track }.use {
            assertEquals(track, it.request(trail).get(5, TimeUnit.SECONDS))
        }
        SavedGeometryCache(root, DerivedRouteCache(root)) { _, _ -> error("XML parse") }.use {
            val result = it.request(trail)
            assertEquals(track, result.get(5, TimeUnit.SECONDS))
            assertSame(result, it.request(trail))
        }
    }

    @Test fun changedLengthAndMtimeEachInvalidateAndRegenerate() = withRoot { root, file ->
        val disk = DerivedRouteCache(root)
        disk.write(trail, track)
        file.appendText("replacement")
        val replaced = track.copy(name = "new")
        var parses = 0
        assertEquals(replaced, disk.load(trail) { parses++; replaced })
        assertEquals(replaced, DerivedRouteCache(root).load(trail) { error("XML parse") })
        assertTrue(file.setLastModified(file.lastModified() + 5000))
        disk.load(trail) { parses++; track }
        assertEquals(2, parses)
    }

    @Test fun changedCanonicalPathInvalidatesEvenWithSameLengthAndTime() = withRoot { root, file ->
        val disk = DerivedRouteCache(root)
        disk.write(trail, track)
        val other = File(root, "other.gpx").apply { writeBytes(file.readBytes()) }
        assertTrue(other.setLastModified(file.lastModified()))
        var parsed = false
        disk.load(trail.copy(gpxLocalPath = other.name)) { parsed = true; track }
        assertTrue(parsed)
    }

    @Test fun changedSchemaFallsBack() = withRoot { root, _ ->
        DerivedRouteCache(root, schema = 0).write(trail, track)
        val events = mutableListOf<String>()
        var parsed = false
        val disk = DerivedRouteCache(root, report = { phase, _, _ -> events.add(phase) })
        disk.load(trail) { parsed = true; track }
        assertTrue(parsed)
        assertTrue("route_cache_invalid" in events)
        assertEquals(track, disk.load(trail) { error("Should regenerate") })
    }

    @Test fun truncatedAndBitCorruptedEntriesFallBackSafely() = withRoot { root, _ ->
        val disk = DerivedRouteCache(root)
        disk.write(trail, track)
        disk.entry(trail.id).writeBytes(byteArrayOf(1, 2, 3))
        var parses = 0
        disk.load(trail) { parses++; track }
        val bytes = disk.entry(trail.id).readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        disk.entry(trail.id).writeBytes(bytes)
        disk.load(trail) { parses++; track }
        assertEquals(2, parses)
        assertEquals(track, disk.load(trail) { error("Should regenerate") })
    }

    @Test fun removalDeletesEntryAndMissingCanonicalCannotUseCache() = withRoot { root, file ->
        val disk = DerivedRouteCache(root)
        disk.write(trail, track)
        disk.remove(trail.id)
        assertFalse(disk.entry(trail.id).exists())
        disk.write(trail, track)
        assertTrue(file.delete())
        assertTrue(runCatching { disk.load(trail) { it.inputStream(); track } }.isFailure)
        assertFalse(disk.entry(trail.id).exists())
    }

    @Test fun replacementDuringParsingDoesNotPersistOldGeometry() = withRoot { root, file ->
        val disk = DerivedRouteCache(root)
        disk.load(trail) { file.appendText("replacement"); track }
        assertFalse(disk.entry(trail.id).exists())
    }

    @Test fun writeFailureDoesNotFailCanonicalLoad() = withRoot { root, _ ->
        File(root, "derived-routes").writeText("directory blocked")
        assertEquals(track, DerivedRouteCache(root).load(trail) { track })
    }

    @Test fun roundTripPreservesSegmentsGapsElevationsAndEndpoints() = withRoot { root, _ ->
        val disk = DerivedRouteCache(root)
        disk.write(trail, track)
        val restored = DerivedRouteCache(root).load(trail) { error("XML parse") }
        assertEquals(track, restored)
        assertEquals(3, restored.segments.size)
        assertEquals(track.endpoints(), restored.endpoints())
        assertEquals(track.endpointMarkers(), restored.endpointMarkers())
        assertEquals(track.distanceMeters, restored.distanceMeters, 0.0)
        val position = PreparedRoute(restored).nearest(TrackPoint(0.0, 0.005))!!
        assertNotNull(position.toAMeters)
        assertNull(position.toBMeters) // The second line remains disconnected after disk restoration.
        assertEquals(1, position.segmentIndex)
    }
}

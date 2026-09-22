package com.trailrelay.app.trails.community

import com.trailrelay.app.trails.GpxParser
import com.trailrelay.app.trails.TrailSource
import org.junit.Assert.*
import org.junit.Test

class CommunityCatalogTest {
    private fun catalog(entry: String, version: String = "1") = """{"schemaVersion":$version,"trails":[$entry]}"""
    private val minimal = """{"id":"ridge","name":"Ridge","gpxUrl":"trails/ridge.gpx"}"""
    private fun invalid(json: String) {
        try { CommunityCatalog.parse(json); fail("Expected invalid catalog") }
        catch (error: Exception) { assertFalse(error.message.isNullOrBlank()) }
    }

    @Test fun parsesLiveCatalogFixture() {
        val entry = CommunityCatalog.parse(javaClass.getResource("/community/catalog-v1.json")!!.readText()).single()
        assertEquals("test-trail", entry.id)
        assertEquals("TrailRelay Test Trail", entry.name)
        assertEquals("Initial community catalog test trail.", entry.description)
        assertEquals("CA", entry.state)
        assertEquals("Test Region", entry.region)
        assertEquals("easy", entry.difficulty)
        assertEquals(listOf("4x4", "sxs"), entry.vehicleTypes)
        assertEquals(listOf("test"), entry.tags)
        assertEquals(1.0, entry.distanceMiles!!, 0.0)
        assertEquals("2026-09-22", entry.updatedAt)
        assertEquals("https://aldogplays.github.io/TrailRelay-Trails/trails/test-trail/trail.gpx", entry.gpxUrl)
    }

    @Test fun optionalMissingFieldsRemainUnknown() {
        val entry = CommunityCatalog.parse(catalog(minimal)).single()
        assertNull(entry.description); assertNull(entry.state); assertNull(entry.region)
        assertNull(entry.difficulty); assertNull(entry.vehicleTypes); assertNull(entry.tags)
        assertNull(entry.distanceMiles); assertNull(entry.updatedAt)
    }

    @Test fun rejectsUnsupportedVersionsWithUsefulError() {
        try { CommunityCatalog.parse(catalog(minimal, "2")); fail() }
        catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("version 1"))
            assertTrue(error.message!!.contains("2"))
        }
        invalid(catalog(minimal, "1.5"))
        invalid(catalog(minimal, "\"1\""))
    }

    @Test fun rejectsMissingOrWrongRequiredFieldsAndDuplicateIds() {
        invalid(catalog("""{"name":"Trail","gpxUrl":"trail.gpx"}"""))
        invalid(catalog(minimal.replace("\"Ridge\"", "null")))
        invalid(catalog(minimal.replace("\"ridge\"", "12")))
        invalid(catalog(minimal.replace("\"trails/ridge.gpx\"", "\"\"")))
        invalid(catalog("$minimal,$minimal"))
    }

    @Test fun rejectsMalformedOptionalDataAndInvalidDocuments() {
        invalid("not json"); invalid("{}"); invalid(catalog(minimal) + " garbage")
        invalid(catalog(minimal.dropLast(1) + """, "distanceMiles":-1}"""))
        invalid(catalog(minimal.dropLast(1) + """, "vehicleTypes":[123]}"""))
    }

    @Test fun acceptsKnownAndFutureVehicleValues() {
        val values = VEHICLE_TYPES + "future-vehicle"
        val entry = CommunityCatalog.parse(catalog(minimal.dropLast(1) +
            ",\"vehicleTypes\":[" + values.joinToString(",") { "\"$it\"" } + "]}")).single()
        assertEquals(values, entry.vehicleTypes)
        values.forEach { assertEquals(listOf(entry), CommunityCatalog.filter(listOf(entry), vehicle = it)) }
    }

    private val entry = CatalogEntry("ridge", "Pine Ridge", "https://example.com/trail.gpx",
        "Rocky ascent", "CA", "Sierra", "hard", listOf("4x4", "hiking"), listOf("forest"))

    @Test fun searchesEachSupportedFieldIgnoringCaseAndWhitespace() {
        listOf("pine", "ROCKY", " ca ", "sierra", "forest").forEach {
            assertEquals(listOf(entry), CommunityCatalog.filter(listOf(entry), it))
        }
        assertTrue(CommunityCatalog.filter(listOf(entry), "desert").isEmpty())
        assertEquals(listOf(entry), CommunityCatalog.filter(listOf(entry), "  "))
    }

    @Test fun combinesSearchAndAllFilters() {
        assertEquals(listOf(entry), CommunityCatalog.filter(listOf(entry), "pine", "ca", "HARD", "4x4"))
        assertTrue(CommunityCatalog.filter(listOf(entry), state = "NV").isEmpty())
        assertTrue(CommunityCatalog.filter(listOf(entry), difficulty = "easy").isEmpty())
        assertTrue(CommunityCatalog.filter(listOf(entry), vehicle = "atv").isEmpty())
        assertTrue(CommunityCatalog.filter(listOf(entry), "desert", "CA", "hard", "4x4").isEmpty())
    }

    @Test fun unknownMetadataDoesNotMatchActiveFilters() {
        val unknown = CommunityCatalog.parse(catalog(minimal)).single()
        assertEquals(listOf(unknown), CommunityCatalog.filter(listOf(unknown)))
        assertTrue(CommunityCatalog.filter(listOf(unknown), state = "CA").isEmpty())
        assertTrue(CommunityCatalog.filter(listOf(unknown), difficulty = "easy").isEmpty())
        assertTrue(CommunityCatalog.filter(listOf(unknown), vehicle = "hiking").isEmpty())
    }

    @Test fun resolvesRelativeAbsoluteAndParentGpxUrls() {
        assertEquals("https://aldogplays.github.io/TrailRelay-Trails/trails/ridge.gpx",
            CommunityCatalog.resolveGpxUrl("trails/ridge.gpx"))
        assertEquals("https://example.com/a.gpx", CommunityCatalog.resolveGpxUrl("https://example.com/a.gpx"))
        assertEquals("https://example.com/a.gpx", CommunityCatalog.resolveGpxUrl("../a.gpx", "https://example.com/catalog/catalog.json"))
        assertEquals("https://example.com/a.gpx", CommunityCatalog.resolveGpxUrl("/a.gpx", "https://example.com/catalog/catalog.json"))
    }

    @Test fun rejectsNonHttpsGpxUrls() {
        listOf("http://example.com/a.gpx", "file:///tmp/a.gpx", "https://user:pass@example.com/a.gpx").forEach {
            invalid(catalog(minimal.replace("trails/ridge.gpx", it)))
        }
    }

    @Test fun communityUpdateIdentitySurvivesNameUrlAndGeometryChanges() {
        val changed = entry.copy(name = "Renamed", gpxUrl = "https://example.com/new.gpx", distanceMiles = 22.0)
        val track = javaClass.getResourceAsStream("/gpx/multi-segment.gpx")!!.use(GpxParser::parse)
        val original = entry.toLocalTrail(track, "trails/old.gpx", 1L)
        val updated = changed.toLocalTrail(track, "trails/new.gpx", 2L)
        assertEquals(original.id, updated.id)
        assertEquals(entry.id, updated.remoteId)
        assertEquals(TrailSource.COMMUNITY, updated.source)
        assertEquals("Renamed", updated.name)
        assertEquals("trails/new.gpx", updated.gpxLocalPath)
        assertEquals(2L, updated.importedAt)
        assertEquals(track.distanceMeters, updated.distanceMeters, 0.0)
        assertNotEquals(22.0 * 1609.344, updated.distanceMeters, 0.01)
        assertNotEquals(communityTrailId(entry.id), communityTrailId("different-id"))
        // Imported IDs are bare SHA-256 hex digests; community IDs cannot collide with them.
        assertTrue(communityTrailId(entry.id).startsWith("community-"))
        assertFalse(communityTrailId(entry.id).matches(Regex("[0-9a-f]{64}")))
    }
}

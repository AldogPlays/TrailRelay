package com.trailrelay.app.offline

import org.json.JSONObject
import com.trailrelay.app.map.MapMode
import org.junit.Assert.*
import org.junit.Test

class OfflineMetadataTest {
    @Test fun legacyVersionOneRemainsAerial() {
        val bytes = JSONObject().put("schema", "trailrelay.offline.usgs-aerial")
            .put("version", 1).put("trailId", "old").put("trailName", "Old")
            .put("createdAt", 1234).put("minZoom", OfflineCoverage.MIN_ZOOM)
            .put("maxZoom", OfflineCoverage.MAX_ZOOM).toString().toByteArray()
        val metadata = OfflineMetadata.decode(bytes)!!
        assertEquals(MapMode.AERIAL, metadata.mapType)
        assertTrue(metadata.belongsTo("old", MapMode.AERIAL))
        assertFalse(metadata.belongsTo("old", MapMode.TOPO))
    }

    @Test fun eachModeRoundTripsAndHasDistinctPackageIdentity() {
        MapMode.entries.forEach { mode ->
            val saved = OfflineMetadata("same", "Trail", 1234, mapType = mode)
            assertEquals(saved, OfflineMetadata.decode(saved.encode()))
            MapMode.entries.forEach { other ->
                assertEquals(mode == other, saved.belongsTo("same", other))
            }
        }
    }

    @Test fun roundTripsUnicodeNamesAndLocalTrailIdentity() {
        val original = OfflineMetadata("community:ridge", "Ridge \"A\" — 峰", 1_780_000_000_000)
        assertEquals(original, OfflineMetadata.decode(original.encode()))
        assertTrue(original.belongsTo("community:ridge", MapMode.AERIAL))
        assertFalse(original.belongsTo("ridge", MapMode.AERIAL))
        assertFalse(original.belongsTo("community:other", MapMode.AERIAL))
        assertTrue(original.copy(trailName = "Renamed trail").belongsTo(original.trailId, MapMode.AERIAL))
    }

    @Test fun distinguishesImportedAndCommunityIdentitiesEvenWithSameName() {
        val imported = OfflineMetadata("abcdef123", "Ridge", 1234)
        val community = OfflineMetadata("community:abcdef123", "Ridge", 1234)
        assertFalse(imported.belongsTo(community.trailId, MapMode.AERIAL))
        assertFalse(community.belongsTo(imported.trailId, MapMode.AERIAL))
    }

    @Test fun corruptMissingAndFutureMetadataIsNotAssignedToATrail() {
        listOf("", "{", "{}", "null", "[]").forEach { assertNull(OfflineMetadata.decode(it.toByteArray())) }
        val original = OfflineMetadata("trail", "Trail", 1234)
        listOf("schema" to "another-app", "version" to 3, "mapType" to "unknown", "trailId" to "", "trailName" to "",
            "createdAt" to 0, "minZoom" to 0, "maxZoom" to 17).forEach { (key, value) ->
            val json = JSONObject(original.encode().toString(Charsets.UTF_8)).put(key, value)
            assertNull(OfflineMetadata.decode(json.toString().toByteArray()))
        }
        val missing = JSONObject(original.encode().toString(Charsets.UTF_8)).apply { remove("trailId") }
        assertNull(OfflineMetadata.decode(missing.toString().toByteArray()))
    }
}

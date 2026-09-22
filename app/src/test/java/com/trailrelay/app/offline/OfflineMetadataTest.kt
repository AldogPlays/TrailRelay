package com.trailrelay.app.offline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineMetadataTest {
    @Test fun roundTripsUnicodeNamesAndLocalTrailIdentity() {
        val original = OfflineMetadata("community:ridge", "Ridge \"A\" — 峰", 1_780_000_000_000)
        assertEquals(original, OfflineMetadata.decode(original.encode()))
        assertTrue(original.belongsTo("community:ridge"))
        assertFalse(original.belongsTo("ridge"))
        assertFalse(original.belongsTo("community:other"))
        assertTrue(original.copy(trailName = "Renamed trail").belongsTo(original.trailId))
    }

    @Test fun distinguishesImportedAndCommunityIdentitiesEvenWithSameName() {
        val imported = OfflineMetadata("abcdef123", "Ridge", 1234)
        val community = OfflineMetadata("community:abcdef123", "Ridge", 1234)
        assertFalse(imported.belongsTo(community.trailId))
        assertFalse(community.belongsTo(imported.trailId))
    }

    @Test fun corruptMissingAndFutureMetadataIsNotAssignedToATrail() {
        listOf("", "{", "{}", "null", "[]").forEach { assertNull(OfflineMetadata.decode(it.toByteArray())) }
        val original = OfflineMetadata("trail", "Trail", 1234)
        listOf("schema" to "another-app", "version" to 2, "trailId" to "", "trailName" to "",
            "createdAt" to 0, "minZoom" to 0, "maxZoom" to 17).forEach { (key, value) ->
            val json = JSONObject(original.encode().toString(Charsets.UTF_8)).put(key, value)
            assertNull(OfflineMetadata.decode(json.toString().toByteArray()))
        }
        val missing = JSONObject(original.encode().toString(Charsets.UTF_8)).apply { remove("trailId") }
        assertNull(OfflineMetadata.decode(missing.toString().toByteArray()))
    }
}

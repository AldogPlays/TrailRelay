package com.trailrelay.app.trails

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UsgsTrailsClientTest {
    private fun response(properties: String = "", extra: String = "") = JsonParser.parseString("""
        {"type":"FeatureCollection", "features":[
            {"type":"Feature", "id":123, "properties":{
                "objectid":123,"trailtype":"Terra Trail", "name":null,
                "permanentidentifier":"retained-id", "publisheddate":null $properties
            },"geometry":{"type":"MultiLineString","coordinates":[[[-105.3,39.7],[-105.31,39.71]]]}}
        ] $extra}
    """).asJsonObject

    @Test fun retainsGeometryAndNullsWithoutInventingAccess() {
        val collection = UsgsTrailsClient.parseFeatures(response(), 1)
        val trail = Trail(collection.features()!!.single())
        assertEquals("Unnamed trail", trail.name)
        assertTrue(trail.details().contains("Motorcycle: Unknown"))
        assertTrue(trail.details().contains("OHV ≤50\": Unknown"))
        assertTrue(trail.details().contains("4WD / OHV >50\": Unknown"))
        assertTrue(trail.feature.getProperty("publisheddate").isJsonNull)
        assertEquals("retained-id", trail.feature.getStringProperty("permanentidentifier"))
        assertEquals("MultiLineString", trail.feature.geometry()!!.type())
    }

    @Test fun understandsBothDomainCodesAndActualServiceAccessValues() {
        val feature = UsgsTrailsClient.parseFeatures(response(""",
            "namealternate":"Alternate name", "motorcycle":"Y",
            "ohvisorunder50inches":"No", "ohvover50inches":"undocumented",
            "primarytrailmaintainer":"FS", "lengthmiles":0.001
        """), 1).features()!!.single()
        val trail = Trail(feature)
        assertEquals("Alternate name", trail.name)
        assertTrue(trail.details().contains("Motorcycle: Yes"))
        assertTrue(trail.details().contains("OHV ≤50\": No"))
        assertTrue(trail.details().contains("4WD / OHV >50\": Unknown"))
        assertTrue(trail.details().contains("Maintainer: USFS"))
        assertTrue(trail.details().contains("Segment length: <0.01 mi"))
        assertEquals("undocumented", feature.getStringProperty("ohvover50inches"))
    }

    @Test(expected = ZoomInRequired::class)
    fun rejectsExplicitTransferLimit() {
        UsgsTrailsClient.parseFeatures(response(extra = ",\"exceededTransferLimit\":true"), 1)
    }

    @Test(expected = ZoomInRequired::class)
    fun rejectsGeoJsonPropertiesTransferLimit() {
        UsgsTrailsClient.parseFeatures(response(extra = ",\"properties\":{\"exceededTransferLimit\":true}"), 1)
    }

    @Test(expected = ZoomInRequired::class)
    fun rejectsUnflaggedTruncation() {
        UsgsTrailsClient.parseFeatures(response(), 2)
    }

    @Test(expected = IOException::class)
    fun rejectsMalformedGeometryInsteadOfSilentlyDroppingIt() {
        val json = response()
        json.getAsJsonArray("features")[0].asJsonObject.add("geometry", null)
        UsgsTrailsClient.parseFeatures(json, 1)
    }

    @Test(expected = ZoomInRequired::class)
    fun refusesCountrySizedEnvelopeBeforeNetworking() {
        UsgsTrailsClient().query(-125.0, 25.0, -65.0, 49.0)
    }

    @Test(expected = ZoomInRequired::class)
    fun refusesWrappedEnvelopeBeforeNetworking() {
        UsgsTrailsClient().query(179.8, 51.0, -179.8, 51.2)
    }
}

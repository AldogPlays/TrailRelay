package com.trailrelay.app.trails

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ZoomInRequired : IOException("Viewport or response exceeds safe trail query limits")

/** Two bounded HTTPS requests, executed by TrailSession's worker, never on the UI thread. */
class UsgsTrailsClient {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false

    fun query(west: Double, south: Double, east: Double, north: Double): FeatureCollection {
        // Refuse broad or date-line-crossing envelopes rather than accidentally querying the world.
        if (!listOf(west, south, east, north).all { it.isFinite() } ||
            west < -180 || east > 180 || south < -90 || north > 90 ||
            east - west !in 0.000001..2.0 || north - south !in 0.000001..2.0) throw ZoomInRequired()
        val spatial = linkedMapOf(
            "where" to "trailtype = 'Terra Trail'",
            "geometry" to "$west,$south,$east,$north",
            "geometryType" to "esriGeometryEnvelope",
            "inSR" to "4326",
            "spatialRel" to "esriSpatialRelIntersects",
        )
        val countResponse = read(spatial + mapOf("f" to "json", "returnCountOnly" to "true"))
        val count = countResponse.get("count")?.asInt ?: throw IOException("USGS count missing")
        if (count > MAX_FEATURES || transferExceeded(countResponse)) throw ZoomInRequired()
        if (count < 0) throw IOException("Invalid USGS count: $count")
        if (count == 0) return FeatureCollection.fromFeatures(emptyList())
        val response = read(spatial + mapOf(
            "f" to "geojson", "outSR" to "4326", "returnGeometry" to "true",
            "returnZ" to "false", "returnM" to "false", "outFields" to FIELDS,
            "resultRecordCount" to MAX_FEATURES.toString(), "orderByFields" to "objectid ASC",
        ))
        return parseFeatures(response, count)
    }

    private fun read(parameters: Map<String, String>): JsonObject {
        if (closed || Thread.currentThread().isInterrupted) throw IOException("Request cancelled")
        val query = parameters.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8") }"
        }
        val request = URL("$ENDPOINT?$query").openConnection() as HttpURLConnection
        connection = request
        try {
            if (closed) throw IOException("Request cancelled")
            request.connectTimeout = 15_000
            request.readTimeout = 20_000
            request.setRequestProperty("Accept", "application/geo+json, application/json")
            if (request.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("USGS HTTP ${request.responseCode}: ${request.responseMessage}")
            }
            val output = ByteArrayOutputStream()
            request.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (closed || Thread.currentThread().isInterrupted) throw IOException("Request cancelled")
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_BYTES) throw ZoomInRequired()
                    output.write(buffer, 0, size)
                }
            }
            val json = JsonParser.parseString(output.toString("UTF-8")).asJsonObject
            if (json.has("error")) throw IOException("USGS query error: ${json.get("error")}")
            return json
        } finally {
            request.disconnect()
            connection = null
        }
    }

    fun close() {
        closed = true
        connection?.disconnect()
    }

    companion object {
        const val ENDPOINT = "https://partnerships.nationalmap.gov/arcgis/rest/services/USGSTrails/MapServer/0/query"
        const val MAX_FEATURES = 2000 // Official layer metadata, inspected for Milestone 2.
        private const val MAX_BYTES = 20 * 1024 * 1024
        const val FIELDS = "objectid,permanentidentifier,name,namealternate,trailnumber,trailtype," +
            "lengthmiles,motorcycle,ohvover50inches,ohvisorunder50inches,ebike,hikerpedestrian," +
            "bicycle,primarytrailmaintainer,nationaltraildesignation,trailsurface,routetype," +
            "seasonopen,sourceoriginator,publisheddate"

        internal fun transferExceeded(json: JsonObject): Boolean =
            json.get("exceededTransferLimit")?.asBoolean == true ||
                json.getAsJsonObject("properties")?.get("exceededTransferLimit")?.asBoolean == true

        internal fun parseFeatures(json: JsonObject, expectedCount: Int): FeatureCollection {
            if (transferExceeded(json)) throw ZoomInRequired()
            if (json.get("type")?.asString != "FeatureCollection") throw IOException("Expected GeoJSON FeatureCollection")
            val features = FeatureCollection.fromJson(json.toString()).features()
                ?: throw IOException("USGS features missing")
            // Also detect servers that truncate GeoJSON without including a transfer-limit flag.
            if (features.size > MAX_FEATURES || features.size != expectedCount) throw ZoomInRequired()
            val ids = HashSet<String>()
            for (feature in features) {
                val id = feature.getProperty("objectid")
                if (id == null || id.isJsonNull || !ids.add(id.asString) ||
                    feature.getProperty("trailtype")?.asString != "Terra Trail" ||
                    (feature.geometry() !is LineString && feature.geometry() !is MultiLineString)) {
                    throw IOException("Invalid or duplicate USGS trail feature")
                }
            }
            return FeatureCollection.fromFeatures(features)
        }
    }
}

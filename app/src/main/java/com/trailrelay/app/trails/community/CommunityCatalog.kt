package com.trailrelay.app.trails.community

import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailSource
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.security.MessageDigest

const val CATALOG_URL = "https://aldogplays.github.io/TrailRelay-Trails/catalog.json"
val VEHICLE_TYPES = listOf("4x4", "sxs", "atv", "motorcycle", "hiking", "bicycle")

/** Remote browsing metadata only; downloaded geometry and persistence use Trail. */
data class CatalogEntry(
    val id: String, val name: String, val gpxUrl: String,
    val description: String? = null, val state: String? = null, val region: String? = null,
    val difficulty: String? = null, val vehicleTypes: List<String>? = null,
    val tags: List<String>? = null, val distanceMiles: Double? = null, val updatedAt: String? = null,
    val sourceUrl: String? = null,
)

object CommunityCatalog {
    fun parse(json: String): List<CatalogEntry> {
        val reader = JSONTokener(json)
        val root = reader.nextValue() as? JSONObject ?: error("Catalog must be a JSON object.")
        require(reader.nextClean() == '\u0000') { "Unexpected data after catalog." }
        val version = root.opt("schemaVersion")
        require(version is Number && version.toDouble() == 1.0) {
            "Unsupported catalog schema version: $version. This app supports version 1."
        }
        val entries = root.opt("trails") as? JSONArray ?: error("Catalog is missing its trails array.")
        val ids = mutableSetOf<String>()
        return (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            fun string(key: String, required: Boolean = false): String? {
                if (!item.has(key) || item.isNull(key)) {
                    require(!required) { "Catalog trail is missing $key." }
                    return null
                }
                val value = item.get(key)
                require(value is String && value.isNotBlank()) { "Invalid catalog field: $key." }
                return value
            }
            fun strings(key: String): List<String>? {
                if (!item.has(key) || item.isNull(key)) return null
                val values = item.getJSONArray(key)
                return (0 until values.length()).map {
                    val value = values.get(it)
                    require(value is String && value.isNotBlank()) { "Invalid catalog field: $key." }
                    value
                }
            }
            val id = string("id", true)!!
            require(ids.add(id)) { "Duplicate catalog id: $id." }
            val distance = if (!item.has("distanceMiles") || item.isNull("distanceMiles")) null else {
                val value = item.get("distanceMiles")
                require(value is Number && value.toDouble().isFinite() && value.toDouble() >= 0) {
                    "Invalid distanceMiles for $id."
                }
                value.toDouble()
            }
            CatalogEntry(id, string("name", true)!!, resolveGpxUrl(string("gpxUrl", true)!!),
                string("description"), string("state"), string("region"), string("difficulty"),
                strings("vehicleTypes"), strings("tags"), distance, string("updatedAt"),
                string("sourceUrl")?.also {
                    val source = URI(it)
                    require(source.scheme?.lowercase() in listOf("https", "http") &&
                        !source.host.isNullOrBlank() && source.userInfo == null) { "Invalid sourceUrl for $id." }
                })
        }
    }

    fun resolveGpxUrl(value: String, catalogUrl: String = CATALOG_URL): String {
        val resolved = URI(catalogUrl).resolve(value)
        require(resolved.scheme.equals("https", true) && !resolved.host.isNullOrBlank() && resolved.userInfo == null) {
            "GPX URLs must use HTTPS."
        }
        return resolved.toString()
    }

    fun filter(entries: List<CatalogEntry>, query: String = "", state: String? = null,
               difficulty: String? = null, vehicle: String? = null): List<CatalogEntry> = entries.filter { entry ->
        val searchable = listOfNotNull(entry.name, entry.description, entry.state, entry.region) + entry.tags.orEmpty()
        (query.isBlank() || searchable.any { it.contains(query.trim(), ignoreCase = true) }) &&
            (state == null || state.equals(entry.state, true)) &&
            (difficulty == null || difficulty.equals(entry.difficulty, true)) &&
            (vehicle == null || entry.vehicleTypes.orEmpty().any { it.equals(vehicle, true) })
    }
}

/** Namespace prevents collisions with imported content hashes; names/URLs/bytes may change. */
fun communityTrailId(remoteId: String): String = "community-" + MessageDigest.getInstance("SHA-256")
    .digest(remoteId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Both sources use the same local Trail representation and parsed GPX geometry. */
internal fun CatalogEntry.toLocalTrail(track: GpxTrack, path: String, savedAt: Long): Trail {
    val points = track.segments.flatten()
    return Trail(communityTrailId(id), name, description, TrailSource.COMMUNITY,
        path, track.distanceMeters, points.minOf { it.latitude }, points.minOf { it.longitude },
        points.maxOf { it.latitude }, points.maxOf { it.longitude }, savedAt, id)
}

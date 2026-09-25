package com.trailrelay.app.offline

import org.json.JSONObject
import com.trailrelay.app.map.MapMode

/** Identity lives with the native offline region; no parallel SQLite index. */
data class OfflineMetadata(
    val trailId: String,
    val trailName: String,
    val createdAt: Long,
    val minZoom: Int = OfflineCoverage.MIN_ZOOM,
    val maxZoom: Int = OfflineCoverage.MAX_ZOOM,
    val mapType: MapMode = MapMode.AERIAL,
) {
    fun belongsTo(id: String, mode: MapMode) = trailId == id && mapType == mode

    fun encode(): ByteArray = JSONObject().put("schema", SCHEMA_V2).put("version", 2)
        .put("trailId", trailId).put("trailName", trailName).put("createdAt", createdAt)
        .put("minZoom", minZoom).put("maxZoom", maxZoom).put("mapType", mapType.id)
        .toString().toByteArray(Charsets.UTF_8)

    companion object {
        private const val SCHEMA_V1 = "trailrelay.offline.usgs-aerial"
        private const val SCHEMA_V2 = "trailrelay.offline.usgs-map"
        fun decode(bytes: ByteArray): OfflineMetadata? = runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val version = json.getInt("version")
            require((version == 1 && json.getString("schema") == SCHEMA_V1) ||
                (version == 2 && json.getString("schema") == SCHEMA_V2))
            val mode = if (version == 1) MapMode.AERIAL else
                MapMode.entries.first { it.id == json.getString("mapType") }
            OfflineMetadata(json.getString("trailId"), json.getString("trailName"),
                json.getLong("createdAt"), json.getInt("minZoom"), json.getInt("maxZoom"), mode).also {
                require(it.trailId.isNotBlank() && it.trailName.isNotBlank() && it.createdAt > 0 &&
                    it.minZoom == OfflineCoverage.MIN_ZOOM && it.maxZoom == OfflineCoverage.MAX_ZOOM)
            }
        }.getOrNull()
    }
}

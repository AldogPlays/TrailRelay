package com.trailrelay.app.offline

import org.json.JSONObject

/** Identity lives with the native offline region; no parallel SQLite index. */
data class OfflineMetadata(
    val trailId: String,
    val trailName: String,
    val createdAt: Long,
    val minZoom: Int = OfflineCoverage.MIN_ZOOM,
    val maxZoom: Int = OfflineCoverage.MAX_ZOOM,
) {
    fun belongsTo(id: String) = trailId == id

    fun encode(): ByteArray = JSONObject().put("schema", SCHEMA).put("version", 1)
        .put("trailId", trailId).put("trailName", trailName).put("createdAt", createdAt)
        .put("minZoom", minZoom).put("maxZoom", maxZoom).toString().toByteArray(Charsets.UTF_8)

    companion object {
        private const val SCHEMA = "trailrelay.offline.usgs-aerial"
        fun decode(bytes: ByteArray): OfflineMetadata? = runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getString("schema") == SCHEMA && json.getInt("version") == 1)
            OfflineMetadata(json.getString("trailId"), json.getString("trailName"),
                json.getLong("createdAt"), json.getInt("minZoom"), json.getInt("maxZoom")).also {
                require(it.trailId.isNotBlank() && it.trailName.isNotBlank() && it.createdAt > 0 &&
                    it.minZoom == OfflineCoverage.MIN_ZOOM && it.maxZoom == OfflineCoverage.MAX_ZOOM)
            }
        }.getOrNull()
    }
}

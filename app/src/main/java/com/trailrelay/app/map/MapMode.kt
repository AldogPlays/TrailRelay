package com.trailrelay.app.map

import android.content.Context
import com.trailrelay.app.R
import com.trailrelay.app.SettingsActivity

/** The three supported USGS basemaps. IDs and style URIs are persistent identities. */
enum class MapMode(val id: String, val label: Int, val styleUri: String,
    val rasterSourceId: String, val credit: Int) {
    AERIAL("aerial", R.string.map_aerial, AERIAL_STYLE_URI, "usgs-imagery", R.string.map_credit_aerial),
    HYBRID("hybrid", R.string.map_hybrid, "asset://usgs_hybrid.json", "usgs-hybrid", R.string.map_credit_hybrid),
    TOPO("topo", R.string.map_topo, "asset://usgs_topo.json", "usgs-topo", R.string.map_credit_topo);

    companion object {
        const val PREFERENCE = "map_mode"
        fun fromId(id: String?): MapMode = entries.firstOrNull { it.id == id } ?: AERIAL
        fun selected(context: Context): MapMode = fromId(context.getSharedPreferences(
            SettingsActivity.PREFERENCES, Context.MODE_PRIVATE).getString(PREFERENCE, null))
    }
}

/** A request identity also guards callbacks from superseded asynchronous style loads. */
internal class MapModeRequests {
    private var generation = 0
    fun next(): Int = ++generation
    fun isCurrent(request: Int): Boolean = request == generation
}

package com.trailrelay.app.map

/** One route can be selected; a Community preview has no persisted route yet. */
sealed interface MapSelection {
    data class Saved(val trailId: String) : MapSelection
    data class Preview(val catalogId: String) : MapSelection
}

class MapSelectionState {
    var selection: MapSelection? = null
        private set

    fun select(next: MapSelection) { selection = next }
    fun clear() { selection = null }
    fun saved(trailId: String) { selection = MapSelection.Saved(trailId) }
    val isBrowsing: Boolean get() = selection == null
}

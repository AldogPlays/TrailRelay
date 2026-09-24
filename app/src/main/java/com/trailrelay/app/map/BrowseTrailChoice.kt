package com.trailrelay.app.map

/** MapLibre can return several segments, including repeated hits for one route. */
fun uniqueTrailHits(renderedIds: List<String?>): List<String> =
    renderedIds.filterNotNull().distinct()

sealed interface MapTapDecision {
    data object None : MapTapDecision
    data class Select(val trailId: String) : MapTapDecision
    data class Choose(val trailIds: List<String>) : MapTapDecision
}

fun mapTapDecision(ids: List<String>): MapTapDecision = when (ids.size) {
    0 -> MapTapDecision.None
    1 -> MapTapDecision.Select(ids.single())
    else -> MapTapDecision.Choose(ids)
}

/** Small fixed palette; a trail keeps its browse identity across launches. */
object BrowseTrailStyle {
    const val COUNT = 4
    fun index(trailId: String): Int = Math.floorMod(trailId.hashCode(), COUNT)
}

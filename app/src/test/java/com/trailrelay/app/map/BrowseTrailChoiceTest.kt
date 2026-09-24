package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test

class BrowseTrailChoiceTest {
    @Test fun oneRenderedTrailSelectsDirectly() {
        assertEquals(MapTapDecision.Select("a"), mapTapDecision(uniqueTrailHits(listOf("a"))))
    }

    @Test fun distinctHitsBecomeChooserCandidatesWithoutDuplicates() {
        assertEquals(MapTapDecision.Choose(listOf("a", "b")),
            mapTapDecision(uniqueTrailHits(listOf("a", "a", null, "b", "b"))))
        assertEquals(MapTapDecision.Select("a"),
            mapTapDecision(uniqueTrailHits(listOf("a", "a"))))
    }

    @Test fun browseStyleIsStableAndWithinCuratedPalette() {
        val first = BrowseTrailStyle.index("trail-a")
        assertEquals(first, BrowseTrailStyle.index("trail-a"))
        assertTrue(first in 0 until BrowseTrailStyle.COUNT)
        assertTrue(listOf("trail-a", "trail-b", "trail-c", "trail-d", "trail-e")
            .map(BrowseTrailStyle::index).distinct().size > 1)
    }
}

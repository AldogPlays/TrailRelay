package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test

class MapOrientationTest {
    @Test fun preferenceDefaultsAndMapsKnownValues() {
        assertEquals(MapOrientation.NORTH_UP, MapOrientation.fromPreference(null))
        assertEquals(MapOrientation.NORTH_UP, MapOrientation.fromPreference("unknown"))
        assertEquals(MapOrientation.HEADING_UP, MapOrientation.fromPreference("HEADING_UP"))
    }

    @Test fun panningSuspendsFollowWithoutChangingOrientation() {
        val state = FollowState(MapOrientation.HEADING_UP)
        state.pan()
        assertFalse(state.following)
        assertEquals(MapOrientation.HEADING_UP, state.orientation)
        state.recenter()
        assertTrue(state.following)
        assertEquals(MapOrientation.HEADING_UP, state.orientation)

        state.orientation = MapOrientation.NORTH_UP
        state.pan()
        state.recenter()
        assertTrue(state.following)
        assertEquals(MapOrientation.NORTH_UP, state.orientation)
    }

    @Test fun pannedOrientationUsesOnlyAvailableHeading() {
        assertEquals(0.0, MapOrientation.NORTH_UP.bearingWhilePanned(null, Long.MAX_VALUE)!!, 0.0)
        assertEquals(270.0, MapOrientation.HEADING_UP.bearingWhilePanned(-90f, 100L)!!, 0.0)
        assertNull(MapOrientation.HEADING_UP.bearingWhilePanned(null, 0L))
        assertNull(MapOrientation.HEADING_UP.bearingWhilePanned(Float.NaN, 0L))
        assertNull(MapOrientation.HEADING_UP.bearingWhilePanned(45f, 5_001L))
    }
}

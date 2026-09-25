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

    @Test fun northUpSelectionKeepsPannedFollowSuspended() {
        val state = FollowState(MapOrientation.HEADING_UP)
        state.pan()
        assertFalse(state.selectOrientation(MapOrientation.NORTH_UP, userInitiated = true))
        assertEquals(MapOrientation.NORTH_UP, state.orientation)
        assertFalse(state.following)
    }

    @Test fun headingUpSelectionResumesPannedFollow() {
        val state = FollowState(MapOrientation.NORTH_UP)
        state.pan()
        assertTrue(state.selectOrientation(MapOrientation.HEADING_UP, userInitiated = true))
        assertEquals(MapOrientation.HEADING_UP, state.orientation)
        assertTrue(state.following)
    }

    @Test fun restoringHeadingPreferenceDoesNotResumePannedFollow() {
        val state = FollowState(MapOrientation.NORTH_UP)
        state.pan()
        assertFalse(state.selectOrientation(MapOrientation.HEADING_UP, userInitiated = false))
        assertEquals(MapOrientation.HEADING_UP, state.orientation)
        assertFalse(state.following)
    }

    @Test fun recenterKeepsTheSelectedOrientation() {
        for (orientation in MapOrientation.entries) {
            val state = FollowState(orientation)
            state.pan()
            state.recenter()
            assertTrue(state.following)
            assertEquals(orientation, state.orientation)
        }
    }
}

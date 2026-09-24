package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test

class MapSelectionStateTest {
    @Test fun selectionReplacesPreviousRouteAndClearsToBrowse() {
        val state = MapSelectionState()
        assertTrue(state.isBrowsing)
        state.select(MapSelection.Saved("local-a"))
        assertEquals(MapSelection.Saved("local-a"), state.selection)
        state.select(MapSelection.Saved("local-b"))
        assertEquals(MapSelection.Saved("local-b"), state.selection)
        state.clear()
        assertTrue(state.isBrowsing)
        assertNull(state.selection)
    }

    @Test fun previewStaysUnsavedUntilExplicitTransition() {
        val state = MapSelectionState()
        state.select(MapSelection.Preview("remote-a"))
        assertEquals(MapSelection.Preview("remote-a"), state.selection)
        state.saved("community-a")
        assertEquals(MapSelection.Saved("community-a"), state.selection)
    }
}

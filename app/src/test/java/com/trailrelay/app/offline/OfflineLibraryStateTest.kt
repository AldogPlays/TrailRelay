package com.trailrelay.app.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineLibraryStateTest {
    @Test fun derivesNativeAndTransientStates() {
        assertEquals(OfflineLibraryState.CHECKING, offlineLibraryState(false, false, false, false, false))
        assertEquals(OfflineLibraryState.DOWNLOADING, offlineLibraryState(true, false, true, false, false))
        assertEquals(OfflineLibraryState.DOWNLOADING, offlineLibraryState(false, false, false, true, false))
        assertEquals(OfflineLibraryState.INCOMPLETE, offlineLibraryState(true, false, false, false, false))
        assertEquals(OfflineLibraryState.FAILED, offlineLibraryState(true, false, false, false, true))
        assertEquals(OfflineLibraryState.COMPLETE, offlineLibraryState(true, true, false, false, false))
    }

    @Test fun resumesOnlyRecognizedIncompleteOrFailedRegions() {
        assertTrue(canResumeOffline(OfflineLibraryState.INCOMPLETE, true))
        assertTrue(canResumeOffline(OfflineLibraryState.FAILED, true))
        assertFalse(canResumeOffline(OfflineLibraryState.DOWNLOADING, true))
        assertFalse(canResumeOffline(OfflineLibraryState.COMPLETE, true))
        assertFalse(canResumeOffline(OfflineLibraryState.INCOMPLETE, false))
    }

    @Test fun reconciliationKeepsOrphansAndDropsOnlyMissingRegionReferences() {
        assertFalse(isOrphanedOffline(true, "trail", setOf("trail")))
        assertTrue(isOrphanedOffline(true, "missing", setOf("trail")))
        assertTrue(isOrphanedOffline(false, null, setOf("trail")))
        assertEquals(setOf(1L), staleRegionIds(setOf(1L, 2L), setOf(2L, 3L)))
    }
}

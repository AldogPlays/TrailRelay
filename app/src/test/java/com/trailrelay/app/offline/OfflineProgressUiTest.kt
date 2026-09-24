package com.trailrelay.app.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineProgressUiTest {
    @Test fun stageFollowsSharedDownloadState() {
        assertEquals(OfflineOperationState.PREPARING,
            offlineOperationState(true, false, null))
        assertEquals(OfflineOperationState.DOWNLOADING,
            offlineOperationState(false, false, OfflineLibraryState.DOWNLOADING))
        assertEquals(OfflineOperationState.PAUSED,
            offlineOperationState(false, false, OfflineLibraryState.INCOMPLETE))
        assertEquals(OfflineOperationState.COMPLETE,
            offlineOperationState(false, false, OfflineLibraryState.COMPLETE))
        assertEquals(OfflineOperationState.DELETING,
            offlineOperationState(false, true, OfflineLibraryState.COMPLETE))
    }

    @Test fun percentageRequiresPreciseTotal() {
        assertNull(preciseOfflinePercent(50, 100, false))
        assertNull(preciseOfflinePercent(50, 0, true))
        assertEquals(50, preciseOfflinePercent(50, 100, true))
        assertEquals(100, preciseOfflinePercent(150, 100, true))
    }
}

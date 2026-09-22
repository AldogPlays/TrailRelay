package com.trailrelay.app.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineProgressTest {
    @Test fun emptyOrImpreciseCountsAreNeverComplete() {
        assertFalse(offlineComplete(0, 0, false))
        assertFalse(offlineComplete(0, 0, true))
        assertFalse(offlineComplete(20, 10, false))
        assertFalse(offlineComplete(9, 10, true))
        assertTrue(offlineComplete(10, 10, true))
        assertTrue(offlineComplete(11, 10, true))
    }

    @Test fun percentageRequiresKnownTotalAndIsBounded() {
        assertNull(offlinePercentage(10, 10, false))
        assertNull(offlinePercentage(0, 0, true))
        assertEquals(25, offlinePercentage(25, 100, true))
        assertEquals(100, offlinePercentage(110, 100, true))
    }
}

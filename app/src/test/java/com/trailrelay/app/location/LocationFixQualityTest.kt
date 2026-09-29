package com.trailrelay.app.location

import org.junit.Assert.*
import org.junit.Test

class LocationFixQualityTest {
    @Test fun freshnessAndAccuracyBoundaries() {
        assertTrue(LocationFixQuality.fresh(20_000, 100f))
        assertFalse(LocationFixQuality.fresh(20_001, 5f))
        assertTrue(LocationFixQuality.follow(20_000, 50f))
        assertFalse(LocationFixQuality.follow(20_000, 51f))
        assertFalse(LocationFixQuality.fresh(-1, 5f))
    }
    @Test fun onlyBlatantJumpsAreFiltered() {
        assertFalse(LocationFixQuality.implausibleJump(100.0, 2_000, 5f, 5f))
        assertTrue(LocationFixQuality.implausibleJump(1_000.0, 2_000, 5f, 5f))
        assertFalse(LocationFixQuality.implausibleJump(1_000.0, 2_000, 100f, 5f))
    }
}

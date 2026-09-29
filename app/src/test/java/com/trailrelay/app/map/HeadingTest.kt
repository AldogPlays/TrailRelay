package com.trailrelay.app.map

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingTest {
    @Test fun eastAndWestDeclinationHaveCorrectSign() {
        assertEquals(32f, trueHeading(20f, 12f), 0.0001f)
        assertEquals(8f, trueHeading(20f, -12f), 0.0001f)
    }
    @Test fun wrapsAcrossNorthInBothDirections() {
        assertEquals(2f, trueHeading(355f, 7f), 0.0001f)
        assertEquals(358f, trueHeading(5f, -7f), 0.0001f)
        assertEquals(0f, trueHeading(360f, 0f), 0.0001f)
        assertEquals(180f, trueHeading(-180f, 0f), 0.0001f)
    }
    @Test fun zeroDeclinationDoesNotApplyAnOffset() {
        for (heading in 0..359) assertEquals(heading.toFloat(), trueHeading(heading.toFloat(), 0f), 0f)
    }
}

package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test

class HeadingSourceTest {
    @Test fun mountOffsetNormalizesWithoutChangingCourse() {
        assertEquals(0f, mountedHeading(0f, 0))
        assertEquals(15f, mountedHeading(355f, 20))
        assertEquals(340f, mountedHeading(10f, -30))
        assertEquals(180f, mountedHeading(0f, 180))
        assertEquals(180f, mountedHeading(0f, -180))
        assertEquals(0f, normalizeHeading(360f))
        val selector = HeadingSourceSelector()
        selector.select(mountedHeading(90f, 45), 0, 3, 210f, 0, 5f, 5f, 5f)
        val decision = selector.select(mountedHeading(90f, 45), 0, 3, 210f, 0, 5f, 5f, 5f)
        assertEquals(HeadingSource.COURSE, decision.source)
        assertEquals(210f, decision.bearing)
    }

    @Test fun courseRequiresFreshReliableMotionAndUsesHysteresis() {
        val selector = HeadingSourceSelector()
        fun choose(speed: Float, age: Long = 0, accuracy: Float = 5f, bearingAccuracy: Float? = 5f) =
            selector.select(90f, 0, 3, 180f, age, accuracy, speed, bearingAccuracy).source
        assertEquals(HeadingSource.COMPASS, choose(4f))
        assertEquals(HeadingSource.COURSE, choose(4f))
        assertEquals(HeadingSource.COURSE, choose(2.5f))
        assertEquals(HeadingSource.NONE, choose(1f))
        assertEquals(HeadingSource.COMPASS, choose(1f))
        assertEquals(HeadingSource.COMPASS, choose(4f, age = 11_000))
        assertEquals(HeadingSource.COMPASS, choose(4f, accuracy = 30f))
        assertEquals(HeadingSource.COMPASS, choose(4f, bearingAccuracy = 40f))
    }

    @Test fun staleOrUnreliableCompassDoesNotDriveBearing() {
        val selector = HeadingSourceSelector()
        assertEquals(HeadingSource.NONE, selector.select(90f, 3_001, 3, null, 0, 5f, 0f, null).source)
        assertEquals(HeadingSource.NONE, selector.select(90f, 0, 0, null, 0, 5f, 0f, null).source)
        assertEquals(HeadingSource.COMPASS, selector.select(90f, 3_000, 2, null, 0, 5f, 0f, null).source)
    }
}

package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test

class AdaptiveHeadingFilterTest {
    @Test fun shortestArcCrossesNorthInBothDirections() {
        assertEquals(2f, shortestHeadingDelta(359f, 1f))
        assertEquals(-2f, shortestHeadingDelta(1f, 359f))
        assertEquals(359f, normalizeHeading(-1f))
        assertEquals(1f, normalizeHeading(361f))
        assertEquals(-180f, shortestHeadingDelta(0f, 180f))
        assertEquals(-180f, shortestHeadingDelta(180f, 0f))
    }

    @Test fun crossingNorthNeverSweepsThroughSouth() {
        val filter = AdaptiveHeadingFilter()
        assertTrue(filter.update(HeadingSource.COMPASS, 359f))
        assertFalse(filter.update(HeadingSource.COMPASS, 1f))
        assertEquals(359.6f, filter.filtered!!, 0.001f)
        assertTrue(filter.update(HeadingSource.COMPASS, 6f))
        assertTrue(filter.displayed!! < 10f || filter.displayed!! > 350f)

        filter.reset()
        assertTrue(filter.update(HeadingSource.COMPASS, 1f))
        assertFalse(filter.update(HeadingSource.COMPASS, 359f))
        assertEquals(0.4f, filter.filtered!!, 0.001f)
    }

    @Test fun deadbandSuppressesJitterButAccumulatedTurnEventuallyMoves() {
        val filter = AdaptiveHeadingFilter()
        filter.update(HeadingSource.COMPASS, 90f)
        assertFalse(filter.update(HeadingSource.COMPASS, 92f))
        assertTrue(filter.deadbandApplied)
        assertEquals(90f, filter.displayed!!)
        assertFalse(filter.update(HeadingSource.COMPASS, 92f))
        assertFalse(filter.update(HeadingSource.COMPASS, 92f))
        assertTrue(filter.update(HeadingSource.COMPASS, 92f))
        assertFalse(filter.deadbandApplied)
        assertTrue(filter.displayed!! > 91.5f)
    }

    @Test fun largerTurnsUseFasterGains() {
        val small = AdaptiveHeadingFilter().apply { update(HeadingSource.COMPASS, 90f) }
        val moderate = AdaptiveHeadingFilter().apply { update(HeadingSource.COMPASS, 90f) }
        val large = AdaptiveHeadingFilter().apply { update(HeadingSource.COMPASS, 90f) }
        small.update(HeadingSource.COMPASS, 94f)
        moderate.update(HeadingSource.COMPASS, 100f)
        large.update(HeadingSource.COMPASS, 120f)
        assertEquals(1.2f, shortestHeadingDelta(90f, small.filtered!!), 0.001f)
        assertEquals(6.5f, shortestHeadingDelta(90f, moderate.filtered!!), 0.001f)
        assertEquals(30f, shortestHeadingDelta(90f, large.filtered!!), 0.001f)
        assertTrue(large.displayed!! > moderate.displayed!!)
    }

    @Test fun courseUsesSlightlyFasterSmallTurnGain() {
        val compass = AdaptiveHeadingFilter().apply { update(HeadingSource.COMPASS, 90f) }
        val course = AdaptiveHeadingFilter().apply { update(HeadingSource.COURSE, 90f) }
        compass.update(HeadingSource.COMPASS, 95f)
        course.update(HeadingSource.COURSE, 95f)
        assertTrue(course.filtered!! > compass.filtered!!)
    }

    @Test fun sourceChangesUseShortestArcAndReseedQuickly() {
        val filter = AdaptiveHeadingFilter()
        filter.update(HeadingSource.COMPASS, 359f)
        assertTrue(filter.update(HeadingSource.COURSE, 4f))
        assertEquals(3f, filter.displayed!!, 0.001f)
        assertTrue(filter.update(HeadingSource.COMPASS, 350f))
        assertTrue(shortestHeadingDelta(3f, filter.displayed!!) < 0f)
        assertTrue(kotlin.math.abs(shortestHeadingDelta(3f, filter.displayed!!)) < 20f)
        // A tiny same-source jitter is damped again after the transition.
        filter.update(HeadingSource.COMPASS, filter.displayed!! + 1f)
        assertTrue(filter.deadbandApplied)
    }

    @Test fun invalidSamplesDoNotPoisonStateAndResetReseeds() {
        val filter = AdaptiveHeadingFilter()
        filter.update(HeadingSource.COMPASS, 45f)
        assertFalse(filter.update(HeadingSource.NONE, null))
        assertFalse(filter.update(HeadingSource.COMPASS, Float.NaN))
        assertEquals(45f, filter.displayed!!)
        assertEquals(45f, filter.filtered!!)
        assertEquals(null, filter.target)
        assertTrue(filter.update(HeadingSource.COMPASS, 75f))
        assertEquals(75f, filter.displayed!!)
        filter.reset()
        assertEquals(null, filter.displayed)
        assertTrue(filter.update(HeadingSource.COURSE, 225f))
        assertEquals(225f, filter.displayed!!)
    }

    @Test fun compassOffsetStillDoesNotTouchCourse() {
        val compass = mountedHeading(355f, 20)
        assertEquals(15f, compass)
        assertEquals(340f, mountedHeading(10f, -30))
        assertEquals(180f, mountedHeading(0f, 180))
        assertEquals(180f, mountedHeading(0f, -180))
        val filter = AdaptiveHeadingFilter()
        filter.update(HeadingSource.COURSE, 210f)
        assertEquals(210f, filter.displayed!!)
    }
}

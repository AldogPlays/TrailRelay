package com.trailrelay.app.location

import org.junit.Assert.*
import org.junit.Test

class SpeedDisplayTest {
    private val second = 1_000_000_000L
    private fun fix(seconds: Long, latitude: Double = 0.0, accuracy: Float = 5f,
                    native: Float? = null) = SpeedFix(latitude, 0.0, accuracy,
        (100L + seconds) * second, native)

    @Test fun convertsMetersPerSecondToMilesPerHour() {
        assertEquals(22, SpeedDisplay.mph(10f, 0L))
        assertEquals(0, SpeedDisplay.mph(0f, 0L))
        assertEquals(0, SpeedDisplay.mph(0.05f, 0L))
    }

    @Test fun unavailableOrStaleSpeedHasNoNumericValue() {
        assertNull(SpeedDisplay.mph(null, 0L))
        assertNull(SpeedDisplay.mph(5f, SpeedDisplay.MAX_AGE_MILLIS + 1))
        assertNull(SpeedDisplay.mph(5f, -1L))
        assertNull(SpeedDisplay.mph(Float.NaN, 0L))
        assertNull(SpeedDisplay.mph(-1f, 0L))
    }

    @Test fun nativeSpeedIncludingZeroTakesPriority() {
        val estimator = SpeedEstimator()
        assertEquals(SpeedReading(0, SpeedSource.NATIVE),
            estimator.accept(fix(0, native = 0f), 100 * second))
        assertEquals(SpeedReading(22, SpeedSource.NATIVE),
            estimator.accept(fix(4, native = 10f), 104 * second))
        estimator.accept(fix(8, latitude = 0.0001), 108 * second)
        assertEquals(SpeedReading(0, SpeedSource.NATIVE),
            estimator.accept(fix(12, latitude = 0.0002, native = 0f), 112 * second))
    }

    @Test fun derivesMovementOnlyFromRecentConsistentWindow() {
        val estimator = SpeedEstimator()
        assertEquals(SpeedSource.UNAVAILABLE, estimator.accept(fix(0), 100 * second).source)
        assertEquals(SpeedSource.UNAVAILABLE,
            estimator.accept(fix(4, latitude = 0.0001), 104 * second).source)
        assertEquals(SpeedReading(6, SpeedSource.DERIVED),
            estimator.accept(fix(8, latitude = 0.0002), 108 * second))
        assertEquals(SpeedReading(null, SpeedSource.STALE), estimator.reading(124 * second))
    }

    @Test fun stationaryJitterSettlesAtZero() {
        val estimator = SpeedEstimator()
        estimator.accept(fix(0), 100 * second)
        estimator.accept(fix(4, latitude = 0.00002), 104 * second)
        assertEquals(SpeedReading(0, SpeedSource.DERIVED),
            estimator.accept(fix(8, latitude = -0.00001), 108 * second))
    }

    @Test fun poorAccuracyAndImpossibleJumpStayUnavailable() {
        val poor = SpeedEstimator()
        listOf(0L, 4L, 8L).forEach { seconds ->
            assertEquals(SpeedSource.UNAVAILABLE,
                poor.accept(fix(seconds, latitude = seconds * 0.0001, accuracy = 50f),
                    (100L + seconds) * second).source)
        }
        val jump = SpeedEstimator()
        jump.accept(fix(0), 100 * second)
        jump.accept(fix(4, latitude = 0.01), 104 * second)
        assertEquals(SpeedSource.UNAVAILABLE,
            jump.accept(fix(8, latitude = 0.02), 108 * second).source)
    }

    @Test fun invalidOrTooCloseTimestampsDoNotProduceDerivedSpeed() {
        val estimator = SpeedEstimator()
        estimator.accept(fix(0), 100 * second)
        estimator.accept(fix(1, latitude = 0.0001), 101 * second)
        assertEquals(SpeedSource.UNAVAILABLE,
            estimator.accept(fix(1, latitude = 0.0002), 101 * second).source)
    }

    @Test fun rejectedNativeSpeedDoesNotReplaceAcceptedReading() {
        val estimator = SpeedEstimator()
        val accepted = estimator.accept(fix(0, native = 0f), 100 * second)
        assertEquals(SpeedReading(0, SpeedSource.NATIVE), accepted)
        assertEquals(accepted, estimator.accept(fix(4, accuracy = 50f, native = 20f), 104 * second))
        assertEquals(accepted, estimator.accept(fix(4, latitude = Double.NaN, native = 20f), 104 * second))
        assertEquals(accepted, estimator.accept(fix(-1, native = 20f), 104 * second))
        assertEquals(accepted, estimator.accept(fix(0, native = 20f), 104 * second))
        assertEquals(SpeedReading(11, SpeedSource.NATIVE),
            estimator.accept(fix(8, native = 5f), 108 * second))
    }

    @Test fun rejectedFixDoesNotBreakDerivedWindow() {
        val estimator = SpeedEstimator()
        estimator.accept(fix(0), 100 * second)
        estimator.accept(fix(4, latitude = 0.0001), 104 * second)
        estimator.accept(fix(5, latitude = 0.00015, accuracy = 50f, native = 25f), 105 * second)
        assertEquals(SpeedReading(6, SpeedSource.DERIVED),
            estimator.accept(fix(8, latitude = 0.0002), 108 * second))
    }
}

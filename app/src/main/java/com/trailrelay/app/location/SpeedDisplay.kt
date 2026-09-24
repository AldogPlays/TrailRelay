package com.trailrelay.app.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object SpeedDisplay {
    const val MAX_AGE_MILLIS = 15_000L
    private const val MPH_PER_MPS = 2.2369362921

    fun mph(metersPerSecond: Float?, ageMillis: Long): Int? {
        if (ageMillis !in 0..MAX_AGE_MILLIS || metersPerSecond == null ||
            !metersPerSecond.isFinite() || metersPerSecond < 0f) return null
        return (metersPerSecond * MPH_PER_MPS).roundToInt()
    }
}

data class SpeedFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedRealtimeNanos: Long,
    val nativeMetersPerSecond: Float?,
)

enum class SpeedSource { NATIVE, DERIVED, UNAVAILABLE, STALE }
data class SpeedReading(val mph: Int?, val source: SpeedSource)

/** Uses only the foreground location stream already supplied to the map puck. */
class SpeedEstimator {
    private val recent = ArrayDeque<SpeedFix>()
    private var latest: SpeedFix? = null

    fun reset() {
        recent.clear()
        latest = null
    }

    fun accept(fix: SpeedFix, nowNanos: Long): SpeedReading {
        val previous = latest
        latest = fix
        val age = ageMillis(fix, nowNanos)
        if (age !in 0..SpeedDisplay.MAX_AGE_MILLIS || !coordinatesValid(fix) ||
            !accuracyValid(fix) || previous?.elapsedRealtimeNanos?.let {
                fix.elapsedRealtimeNanos <= it
            } == true) {
            recent.clear()
            return reading(nowNanos)
        }
        recent.addLast(fix)
        while (recent.isNotEmpty() &&
            fix.elapsedRealtimeNanos - recent.first().elapsedRealtimeNanos > WINDOW_NANOS) {
            recent.removeFirst()
        }
        return reading(nowNanos)
    }

    fun reading(nowNanos: Long): SpeedReading {
        val fix = latest ?: return SpeedReading(null, SpeedSource.UNAVAILABLE)
        val age = ageMillis(fix, nowNanos)
        if (age !in 0..SpeedDisplay.MAX_AGE_MILLIS) return SpeedReading(null, SpeedSource.STALE)
        SpeedDisplay.mph(fix.nativeMetersPerSecond, age)?.let {
            return SpeedReading(it, SpeedSource.NATIVE)
        }
        val derived = derivedMetersPerSecond() ?: return SpeedReading(null, SpeedSource.UNAVAILABLE)
        return SpeedReading(SpeedDisplay.mph(derived, age), SpeedSource.DERIVED)
    }

    private fun derivedMetersPerSecond(): Float? {
        if (recent.size < 3 || recent.lastOrNull() !== latest) return null
        val fixes = recent.toList()
        val first = fixes.first()
        val last = fixes.last()
        val spanSeconds = (last.elapsedRealtimeNanos - first.elapsedRealtimeNanos) / 1e9
        if (spanSeconds < MIN_SPAN_SECONDS) return null
        if (fixes.zipWithNext().any { (a, b) ->
                (b.elapsedRealtimeNanos - a.elapsedRealtimeNanos) < MIN_STEP_NANOS
            }) return null

        val accuracy = fixes.maxOf { it.accuracyMeters.toDouble() }
        val distancesFromStart = fixes.map { distanceMeters(first, it) }
        // A cluster within measurement noise can be treated as stationary.
        val stationaryRadius = max(8.0, accuracy * 0.75)
        if (distancesFromStart.max() <= stationaryRadius) return 0f

        val net = distancesFromStart.last()
        // Require displacement beyond uncertainty and a mostly consistent path.
        val movementThreshold = max(15.0, accuracy * 1.5)
        if (net < movementThreshold) return null
        val steps = fixes.zipWithNext().map { (a, b) ->
            val seconds = (b.elapsedRealtimeNanos - a.elapsedRealtimeNanos) / 1e9
            val distance = distanceMeters(a, b)
            if (distance / seconds > MAX_STEP_MPS) return null
            distance
        }
        val speed = net / spanSeconds
        if (speed > MAX_DERIVED_MPS || steps.sum() > net * 1.7) return null
        return speed.toFloat()
    }

    private fun ageMillis(fix: SpeedFix, nowNanos: Long): Long =
        if (fix.elapsedRealtimeNanos <= 0L || nowNanos < fix.elapsedRealtimeNanos) -1L else
            (nowNanos - fix.elapsedRealtimeNanos) / 1_000_000L

    private fun coordinatesValid(fix: SpeedFix) =
        fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
            fix.longitude.isFinite() && fix.longitude in -180.0..180.0

    private fun accuracyValid(fix: SpeedFix) =
        fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..MAX_ACCURACY_METERS &&
            fix.accuracyMeters > 0f

    private fun distanceMeters(a: SpeedFix, b: SpeedFix): Double {
        val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
        val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
        val h = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val WINDOW_NANOS = 12_000_000_000L
        const val MIN_STEP_NANOS = 1_000_000_000L
        const val MIN_SPAN_SECONDS = 6.0
        const val MAX_ACCURACY_METERS = 25f
        const val MAX_STEP_MPS = 45.0
        const val MAX_DERIVED_MPS = 35.0
    }
}

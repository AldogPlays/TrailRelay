package com.trailrelay.app.map

import android.hardware.GeomagneticField
import android.location.Location
import android.os.Build
import android.os.SystemClock
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener

/** Retains MapLibre's sensor fusion and display/tilt remapping; corrects north exactly once. */
class TrueNorthCompass(private val magnetic: CompassEngine) : CompassEngine, CompassListener {
    private val listeners = linkedSetOf<CompassListener>()
    private val selector = HeadingSourceSelector()
    val filter = AdaptiveHeadingFilter()
    var onSample: (() -> Unit)? = null
    private var offset = 0
    private var sensorTime = 0L
    private var course: Float? = null
    private var fixTime = 0L
    private var accuracyM = Float.POSITIVE_INFINITY
    private var speedMps: Float? = null
    private var bearingAccuracy: Float? = null
    var decision = HeadingDecision(HeadingSource.NONE, null, "no_sensor")
        private set
    var declination: Float? = null
        private set
    var rawHeading: Float? = null
        private set

    fun updateLocation(location: Location) {
        declination = GeomagneticField(location.latitude.toFloat(), location.longitude.toFloat(),
            if (location.hasAltitude() && location.altitude.isFinite()) location.altitude.toFloat() else 0f,
            System.currentTimeMillis()).declination
        course = location.bearing.takeIf { location.hasBearing() }
        fixTime = location.elapsedRealtimeNanos / 1_000_000L
        accuracyM = location.accuracy
        speedMps = location.speed.takeIf { location.hasSpeed() }
        bearingAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasBearingAccuracy())
            location.bearingAccuracyDegrees else null
        publish(newCourseFix = true)
        // The next sensor callback publishes this correction, avoiding replay of an old sample.
    }

    override fun addCompassListener(listener: CompassListener) {
        if (listeners.add(listener) && listeners.size == 1) magnetic.addCompassListener(this)
    }
    override fun removeCompassListener(listener: CompassListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) magnetic.removeCompassListener(this)
    }
    fun setOffset(degrees: Int) {
        val next = degrees.coerceIn(-180, 180)
        if (next == offset) return
        offset = next
        // A deliberate mount adjustment should take effect immediately, not be learned slowly.
        filter.reset()
        publish()
    }
    val headingOffset get() = offset
    val sensorAgeMs get() = if (sensorTime == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - sensorTime
    val courseAgeMs get() = if (fixTime == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - fixTime
    val correctedHeading get() = rawHeading?.let { trueHeading(it, declination ?: 0f) }
    fun pause() {
        sensorTime = 0L; rawHeading = null; fixTime = 0L; course = null
        selector.reset(); decision = HeadingDecision(HeadingSource.NONE, null, "paused")
        filter.reset()
    }
    override fun getLastHeading(): Float = filter.displayed?.takeIf {
        (decision.source == HeadingSource.COMPASS && sensorAgeMs <= 3_000L) ||
            (decision.source == HeadingSource.COURSE && courseAgeMs <= 10_000L)
    } ?: 0f
    override fun getLastAccuracySensorStatus(): Int = magnetic.lastAccuracySensorStatus
    override fun onCompassChanged(heading: Float) {
        if (!heading.isFinite()) return
        rawHeading = heading
        sensorTime = SystemClock.elapsedRealtime()
        publish()
    }
    override fun onCompassAccuracyChange(status: Int) {
        listeners.forEach { it.onCompassAccuracyChange(status) }
        publish()
    }
    private fun publish(newCourseFix: Boolean = false) {
        val corrected = correctedHeading?.let { mountedHeading(it, offset) }
        val next = selector.select(corrected, sensorAgeMs, magnetic.lastAccuracySensorStatus,
            course, courseAgeMs, accuracyM, speedMps, bearingAccuracy, newCourseFix)
        decision = next
        val changed = filter.update(next.source, next.bearing)
        onSample?.invoke()
        if (changed) filter.displayed?.let { bearing ->
            listeners.toList().forEach { it.onCompassChanged(bearing) }
        }
    }
}

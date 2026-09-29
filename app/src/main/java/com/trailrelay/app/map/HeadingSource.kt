package com.trailrelay.app.map

enum class HeadingSource { COMPASS, COURSE, NONE }
data class HeadingDecision(val source: HeadingSource, val bearing: Float?, val reason: String)

/** Course is travel direction, compass is device direction. GPS course needs a good fix and
 * sustained motion; 3/2 m/s hysteresis avoids bouncing around walking/vehicle transitions.
 * Two consecutive qualifying fixes enter course mode, two exits leave it. These are quality
 * gates, not heading corrections. Absent bearing accuracy requires a better horizontal fix.
 */
class HeadingSourceSelector {
    private var courseActive = false
    private var entering = 0
    private var leaving = 0
    fun reset() { courseActive = false; entering = 0; leaving = 0 }
    fun select(compass: Float?, compassAgeMs: Long, compassAccuracy: Int,
               course: Float?, fixAgeMs: Long, horizontalAccuracyM: Float,
               speedMps: Float?, bearingAccuracyDegrees: Float?, newCourseFix: Boolean = true): HeadingDecision {
        val goodCourse = course?.isFinite() == true && course in 0f..360f &&
            fixAgeMs in 0..10_000 && horizontalAccuracyM.isFinite() &&
            horizontalAccuracyM <= (if (bearingAccuracyDegrees == null) 15f else 25f) &&
            (bearingAccuracyDegrees == null || bearingAccuracyDegrees.isFinite() && bearingAccuracyDegrees <= 25f) &&
            speedMps?.isFinite() == true && speedMps >= (if (courseActive) 2f else 3f)
        if (newCourseFix) {
            if (goodCourse) { entering++; leaving = 0; if (entering >= 2) courseActive = true }
            else { leaving++; entering = 0; if (leaving >= 2) courseActive = false }
        }
        if (!goodCourse && fixAgeMs > 10_000) courseActive = false
        if (courseActive && goodCourse) return HeadingDecision(HeadingSource.COURSE, normalizeHeading(course!!), "moving_course")
        if (courseActive) return HeadingDecision(HeadingSource.NONE, null, "course_transition")
        val goodCompass = compass?.isFinite() == true && compassAgeMs in 0..3_000 && compassAccuracy >= 2
        return if (goodCompass) HeadingDecision(HeadingSource.COMPASS, normalizeHeading(compass!!), "fresh_compass")
        else HeadingDecision(HeadingSource.NONE, null, if (compassAgeMs !in 0..3_000) "stale_compass" else "compass_accuracy")
    }
}

fun normalizeHeading(value: Float) = ((value % 360f) + 360f) % 360f
fun mountedHeading(compassTrue: Float, offset: Int) = normalizeHeading(compassTrue + offset)

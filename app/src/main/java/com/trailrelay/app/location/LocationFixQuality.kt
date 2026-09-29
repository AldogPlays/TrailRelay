package com.trailrelay.app.location

/** Conservative field-display gates. The puck remains at reported coordinates, never snapped. */
object LocationFixQuality {
    const val MAX_AGE_MS = 20_000L // The 2 s request interval permits gaps, not two-minute-old fixes.
    const val FOLLOW_ACCURACY_M = 50f // Wider fixes keep an uncertainty puck but do not steer camera.
    fun fresh(ageMs: Long, accuracyM: Float) = ageMs in 0..MAX_AGE_MS &&
        accuracyM.isFinite() && accuracyM > 0
    fun follow(ageMs: Long, accuracyM: Float) = fresh(ageMs, accuracyM) && accuracyM <= FOLLOW_ACCURACY_M
    fun implausibleJump(distanceM: Double, intervalMs: Long, priorAccuracyM: Float, accuracyM: Float): Boolean {
        if (intervalMs !in 1..20_000 || priorAccuracyM > FOLLOW_ACCURACY_M || accuracyM > FOLLOW_ACCURACY_M) return false
        // 70 m/s (~250 km/h) plus 150 m or three reported errors; only blatant GPS jumps fail.
        return distanceM > maxOf(150.0, (priorAccuracyM + accuracyM) * 3.0) + 70.0 * intervalMs / 1000.0
    }
}

package com.trailrelay.app.map

import kotlin.math.abs

/** Signed shortest turn from [from] to [to]. An exact half-turn consistently uses -180°. */
fun shortestHeadingDelta(from: Float, to: Float): Float =
    ((to - from + 540f) % 360f) - 180f

/** Small numeric filter after source selection, before MapLibre's 500 ms camera animation.
 * The internal estimate keeps accumulating even when the visual deadband holds the camera.
 */
class AdaptiveHeadingFilter {
    var target: Float? = null
        private set
    var filtered: Float? = null
        private set
    var displayed: Float? = null
        private set
    var angularDelta = 0f
        private set
    var deadbandApplied = false
        private set
    private var source = HeadingSource.NONE

    fun reset() {
        target = null
        filtered = null
        displayed = null
        angularDelta = 0f
        deadbandApplied = false
        source = HeadingSource.NONE
    }

    /** Returns true only when MapLibre should receive a new visual bearing. */
    fun update(nextSource: HeadingSource, nextBearing: Float?): Boolean {
        if (nextSource == HeadingSource.NONE || nextBearing == null || !nextBearing.isFinite()) {
            target = null
            angularDelta = 0f
            deadbandApplied = false
            return false // An invalid sample must not alter the valid filter state.
        }
        val normalized = normalizeHeading(nextBearing)
        target = normalized
        val current = filtered
        if (current == null) {
            filtered = normalized
            displayed = normalized
            source = nextSource
            angularDelta = 0f
            deadbandApplied = false
            return true
        }

        val changedSource = source != nextSource
        val start = if (changedSource) displayed ?: current else current
        val delta = shortestHeadingDelta(start, normalized)
        angularDelta = delta
        val magnitude = abs(delta)
        // MapLibre already animates over 500 ms. Only tiny jitter gets heavy damping;
        // moderate turns catch up quickly and turns above 20° add no filter delay.
        val gain = when {
            magnitude >= 20f -> 1f
            changedSource -> 0.8f // Avoid dragging old-source state into the new source.
            magnitude >= 6f -> if (nextSource == HeadingSource.COURSE) 0.75f else 0.65f
            nextSource == HeadingSource.COURSE -> 0.4f
            else -> 0.3f
        }
        val estimate = normalizeHeading(start + delta * gain)
        filtered = estimate
        source = nextSource
        val visualDelta = shortestHeadingDelta(displayed ?: start, estimate)
        // 1.5° suppresses map twitch, but the estimate keeps moving under the deadband.
        deadbandApplied = abs(visualDelta) < 1.5f
        if (deadbandApplied) return false
        displayed = estimate
        return true
    }
}

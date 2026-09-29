package com.trailrelay.app.trails

import kotlin.math.*

data class RoutePosition(
    val coordinate: TrackPoint,
    val segmentIndex: Int,
    val edgeIndex: Int,
    val edgeFraction: Double,
    val fromRouteMeters: Double,
    val segmentMeters: Double,
    /** Sum of recorded geometry only; never includes gaps. */
    val cumulativeMeters: Double,
    val toAMeters: Double?,
    val toBMeters: Double?,
)

/** Prepared once per selection. Projection is onto great-circle arcs using the GPX distance radius.
 * Every GPX segment is a separate connected component, even if its ends happen to touch.
 */
class PreparedRoute(track: GpxTrack) {
    private data class Vector(val x: Double, val y: Double, val z: Double) {
        fun dot(other: Vector) = x * other.x + y * other.y + z * other.z
        fun point() = TrackPoint(Math.toDegrees(atan2(z, hypot(x, y))), Math.toDegrees(atan2(y, x)))
    }
    private data class Edge(val segment: Int, val index: Int, val a: Vector, val b: Vector,
        val tangent: Vector, val angle: Double, val length: Double, val offset: Double,
        val cumulative: Double)
    private val edges: List<Edge>
    val isValid: Boolean get() = edges.isNotEmpty()
    private val lengths = mutableMapOf<Int, Double>()
    private val firstSegment: Int?
    private val lastSegment: Int?

    init {
        val prepared = ArrayList<Edge>()
        var cumulative = 0.0
        track.segments.forEachIndexed { segmentIndex, segment ->
            var offset = 0.0
            var continuous = segment.all { it.validCoordinate() }
            segment.zipWithNext().forEachIndexed edgeLoop@ { index, (a, b) ->
                if (!a.validCoordinate() || !b.validCoordinate()) return@edgeLoop
                val length = distanceMeters(a, b)
                val angle = length / EARTH_RADIUS
                // Coincident or antipodal endpoints do not define a unique navigable arc.
                if (angle < 1e-12) return@edgeLoop
                if (PI - angle < 1e-9) { continuous = false; return@edgeLoop }
                val av = vector(a)
                val bv = vector(b)
                val c = cos(angle)
                val s = sin(angle)
                val tangent = Vector((bv.x - av.x * c) / s, (bv.y - av.y * c) / s,
                    (bv.z - av.z * c) / s)
                prepared.add(Edge(segmentIndex, index, av, bv, tangent, angle, length, offset, cumulative))
                offset += length
                cumulative += length
            }
            // Invalid coordinates split connectivity: don't offer distances across them.
            if (offset > 0 && continuous) lengths[segmentIndex] = offset
        }
        edges = prepared
        firstSegment = edges.firstOrNull()?.segment
        lastSegment = edges.lastOrNull()?.segment
    }

    fun nearest(point: TrackPoint): RoutePosition? {
        if (!point.validCoordinate()) return null
        val q = vector(point)
        var bestScore = -Double.MAX_VALUE
        var best: Edge? = null
        var bestAngle = 0.0
        for (edge in edges) {
            val along = atan2(q.dot(edge.tangent), q.dot(edge.a))
            val projected = if (along in 0.0..edge.angle) along else
                if (q.dot(edge.a) >= q.dot(edge.b)) 0.0 else edge.angle
            val score = q.dot(edge.a) * cos(projected) + q.dot(edge.tangent) * sin(projected)
            if (score > bestScore) {
                bestScore = score
                best = edge
                bestAngle = projected
            }
        }
        val edge = best ?: return null
        val c = cos(bestAngle)
        val s = sin(bestAngle)
        val projected = Vector(edge.a.x * c + edge.tangent.x * s,
            edge.a.y * c + edge.tangent.y * s, edge.a.z * c + edge.tangent.z * s).point()
        val fraction = (bestAngle / edge.angle).coerceIn(0.0, 1.0)
        val along = edge.offset + edge.length * fraction
        return RoutePosition(projected, edge.segment, edge.index, fraction, distanceMeters(point, projected),
            along, edge.cumulative + edge.length * fraction,
            along.takeIf { edge.segment == firstSegment && lengths.containsKey(edge.segment) },
            lengths[edge.segment]?.minus(along)?.coerceAtLeast(0.0)?.takeIf { edge.segment == lastSegment })
    }

    private fun vector(p: TrackPoint): Vector {
        val lat = Math.toRadians(p.latitude)
        val lon = Math.toRadians(p.longitude)
        return Vector(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
    }
    private companion object { const val EARTH_RADIUS = 6_371_008.8 }
}

private fun TrackPoint.validCoordinate() = latitude.isFinite() && latitude in -90.0..90.0 &&
    longitude.isFinite() && longitude in -180.0..180.0

/** Accuracy is an uncertainty estimate, so proximity is deliberately phrased as "Near trail". */
object TrailPositionQuality {
    const val MAX_AGE_MILLIS = 15_000L
    fun usable(ageMillis: Long, accuracy: Float) = ageMillis in 0..MAX_AGE_MILLIS &&
        accuracy.isFinite() && accuracy > 0f && accuracy <= 25f
    fun nearTrail(distance: Double, accuracy: Float) = distance <= max(10.0, accuracy.toDouble())
    fun roundingFeet(accuracy: Float) = if (accuracy > 15f) 100 else 50
    fun roundedFeet(meters: Double, accuracy: Float): Int {
        val step = roundingFeet(accuracy)
        return ((meters / 0.3048 / step).roundToInt() * step).coerceAtLeast(step)
    }
}

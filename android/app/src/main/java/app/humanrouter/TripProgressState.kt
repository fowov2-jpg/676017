package app.humanrouter

import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class TripProgressPhase {
    APPROACH,
    WAITING,
    ONBOARD,
    ALIGHTING,
    TRANSFER,
    FINAL_WALK,
    FINISHED,
    OFF_ROUTE
}

data class TripProgressSnapshot(
    val routeId: String,
    val legIndex: Int,
    val phase: TripProgressPhase,
    val point: GeoPoint,
    val epochSec: Long,
    val accuracyMeters: Float,
    val fraction: Float,
    val distanceFromRouteMeters: Double,
    val distanceRemainingMeters: Int,
    val remainingStops: Int,
    val nextLegIndex: Int?
)

/**
 * Passenger-position source of truth for an active trip.
 *
 * Production location and deterministic QA replay both publish through this object, therefore the
 * transfer UI is tested with the same state machine that consumes real GPS samples. Progress is
 * monotonic for one route id: noisy coordinates cannot jump back to an already completed leg.
 */
internal object TripProgressState {
    private val listeners = CopyOnWriteArraySet<(TripProgressSnapshot) -> Unit>()

    @Volatile
    private var current: TripProgressSnapshot? = null

    fun current(): TripProgressSnapshot? = current

    @Synchronized
    fun publishLocation(
        route: RouteCandidate,
        point: GeoPoint,
        epochSec: Long,
        accuracyMeters: Float = 8f
    ): TripProgressSnapshot {
        val previous = current?.takeIf { it.routeId == route.id }
        val snapshot = TripProgressTracker.update(
            route = route,
            point = point,
            epochSec = epochSec,
            accuracyMeters = accuracyMeters,
            previous = previous
        )
        current = snapshot
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
        return snapshot
    }

    @Synchronized
    fun clear() {
        current = null
    }

    fun addListener(listener: (TripProgressSnapshot) -> Unit) {
        listeners += listener
        current?.let { snapshot -> runCatching { listener(snapshot) } }
    }

    fun removeListener(listener: (TripProgressSnapshot) -> Unit) {
        listeners -= listener
    }
}

internal object TripProgressTracker {
    private const val EARTH_RADIUS_M = 6_371_000.0

    private data class Projection(
        val distanceMeters: Double,
        val fraction: Double,
        val totalMeters: Double
    )

    private data class Candidate(
        val index: Int,
        val leg: RouteLeg,
        val projection: Projection,
        val score: Double
    )

    fun update(
        route: RouteCandidate,
        point: GeoPoint,
        epochSec: Long,
        accuracyMeters: Float,
        previous: TripProgressSnapshot?
    ): TripProgressSnapshot {
        val previousIndex = previous?.legIndex ?: 0
        val candidates = route.legs.mapIndexed { index, leg ->
            val projection = project(point, leg.mapPoints())
            val timePenalty = when {
                epochSec < leg.departureEpochSec -> min(650.0, (leg.departureEpochSec - epochSec) * 0.55)
                epochSec > leg.arrivalEpochSec -> min(650.0, (epochSec - leg.arrivalEpochSec) * 0.55)
                else -> 0.0
            }
            val backwardPenalty = if (previous != null && index < previousIndex) 100_000.0 else 0.0
            val skipPenalty = if (previous != null && index > previousIndex + 2) (index - previousIndex - 2) * 45.0 else 0.0
            Candidate(index, leg, projection, projection.distanceMeters + timePenalty + backwardPenalty + skipPenalty)
        }
        val best = candidates.minByOrNull { it.score }
            ?: error("Active route must contain at least one leg")

        val onRouteLimit = max(220.0, accuracyMeters.coerceAtLeast(1f) * 3.0)
        if (best.projection.distanceMeters > onRouteLimit && previous != null) {
            return previous.copy(
                phase = TripProgressPhase.OFF_ROUTE,
                point = point,
                epochSec = epochSec,
                accuracyMeters = accuracyMeters,
                distanceFromRouteMeters = best.projection.distanceMeters
            )
        }

        val index = if (previous == null) best.index else max(previousIndex, best.index)
        val selected = candidates.first { it.index == index }
        val leg = selected.leg
        val geometryFraction = selected.projection.fraction
        val timedFraction = if (leg.arrivalEpochSec > leg.departureEpochSec) {
            ((epochSec - leg.departureEpochSec).toDouble() /
                (leg.arrivalEpochSec - leg.departureEpochSec).toDouble()).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        var fraction = if (selected.projection.totalMeters < 1.0) timedFraction else geometryFraction
        if (previous != null && previous.legIndex == index) fraction = max(previous.fraction.toDouble(), fraction)
        fraction = fraction.coerceIn(0.0, 1.0)

        val destinationDistance = project(point, listOf(route.legs.last().to.point)).distanceMeters
        val phase = when {
            index == route.legs.lastIndex && destinationDistance <= max(28.0, accuracyMeters * 1.5) ->
                TripProgressPhase.FINISHED
            leg.mode == TransportMode.WALK && index == 0 -> TripProgressPhase.APPROACH
            leg.mode == TransportMode.WALK && index == route.legs.lastIndex -> TripProgressPhase.FINAL_WALK
            leg.mode == TransportMode.WALK -> TripProgressPhase.TRANSFER
            epochSec <= leg.departureEpochSec + 20L && fraction <= 0.10 -> TripProgressPhase.WAITING
            fraction >= 0.78 -> TripProgressPhase.ALIGHTING
            else -> TripProgressPhase.ONBOARD
        }

        val remainingStops = if (leg.stopCount > 0) {
            ceil(leg.stopCount * (1.0 - fraction)).toInt().coerceIn(0, leg.stopCount)
        } else {
            0
        }
        val remainingMeters = if (selected.projection.totalMeters > 0.0) {
            (selected.projection.totalMeters * (1.0 - fraction)).toInt().coerceAtLeast(0)
        } else {
            0
        }

        return TripProgressSnapshot(
            routeId = route.id,
            legIndex = index,
            phase = phase,
            point = point,
            epochSec = epochSec,
            accuracyMeters = accuracyMeters,
            fraction = fraction.toFloat(),
            distanceFromRouteMeters = selected.projection.distanceMeters,
            distanceRemainingMeters = remainingMeters,
            remainingStops = remainingStops,
            nextLegIndex = (index + 1).takeIf { it <= route.legs.lastIndex }
        )
    }

    /** Nearest point on an ordered polyline plus travelled fraction along that polyline. */
    private fun project(point: GeoPoint, rawPoints: List<GeoPoint>): Projection {
        val points = rawPoints.distinct()
        if (points.isEmpty()) return Projection(Double.MAX_VALUE, 0.0, 0.0)
        if (points.size == 1) return Projection(distanceMeters(point, points.first()), 0.0, 0.0)

        val segmentLengths = DoubleArray(points.lastIndex) { index ->
            distanceMeters(points[index], points[index + 1])
        }
        val total = segmentLengths.sum()
        if (total < 0.5) return Projection(distanceMeters(point, points.first()), 0.0, total)

        var bestDistance = Double.MAX_VALUE
        var bestAlong = 0.0
        var prefix = 0.0
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            val segment = segmentLengths[index]
            if (segment < 0.01) continue
            val local = projectSegment(point, a, b)
            if (local.first < bestDistance) {
                bestDistance = local.first
                bestAlong = prefix + segment * local.second
            }
            prefix += segment
        }
        return Projection(bestDistance, (bestAlong / total).coerceIn(0.0, 1.0), total)
    }

    /** Pair(distance metres, fraction a->b). */
    private fun projectSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
        val refLat = ((a.lat + b.lat + p.lat) / 3.0) * PI / 180.0
        fun x(point: GeoPoint): Double = point.lon * PI / 180.0 * cos(refLat) * EARTH_RADIUS_M
        fun y(point: GeoPoint): Double = point.lat * PI / 180.0 * EARTH_RADIUS_M

        val ax = x(a)
        val ay = y(a)
        val bx = x(b)
        val by = y(b)
        val px = x(p)
        val py = y(p)
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        val t = if (length2 <= 1e-6) 0.0 else (((px - ax) * dx + (py - ay) * dy) / length2).coerceIn(0.0, 1.0)
        val qx = ax + dx * t
        val qy = ay + dy * t
        val distance = sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
        return distance to t
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val refLat = ((a.lat + b.lat) / 2.0) * PI / 180.0
        val dx = (b.lon - a.lon) * PI / 180.0 * cos(refLat) * EARTH_RADIUS_M
        val dy = (b.lat - a.lat) * PI / 180.0 * EARTH_RADIUS_M
        return sqrt(dx * dx + dy * dy)
    }
}

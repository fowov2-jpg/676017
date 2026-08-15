package app.humanrouter.routing

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bounded multimodal beam search. It composes real surface timetable routes with the rail graph.
 * Every next stage starts at the actual arrival epoch of the previous stage.
 */
internal class MultimodalComposer(
    private val surface: SurfaceCsaRouter,
    private val rail: RailGraphRouter,
    private val railIndex: RailWaypointIndex,
    private val serviceMidnightEpochSec: Long
) {
    fun findCandidates(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long,
        broadSearch: Boolean
    ): List<RouteCandidate> {
        val beam = if (broadSearch) BROAD_BEAM else FAST_BEAM
        val entries = railIndex.nearest(origin, ENTRY_MAX_METERS, beam)
        val exits = railIndex.nearest(destination, EXIT_MAX_METERS, beam)
        if (entries.isEmpty() || exits.isEmpty()) return emptyList()

        val results = LinkedHashMap<String, RouteCandidate>()
        val firstSurface = entries.mapNotNull { entry ->
            surfaceStage(origin, entry.point, departureEpochSec, "Откуда", entry.name)
                ?.takeIf(::usesSurfaceTransit)
                ?.let { entry to it }
        }

        // BUS/TRAM -> METRO/MCC -> WALK.
        for ((entry, first) in firstSurface) {
            rail.findFastest(
                origin = entry.point,
                destination = destination,
                departureEpochSec = first.arrivalEpochSec,
                originName = entry.name,
                destinationName = "Куда"
            )?.let { second ->
                combine(first, second)?.let { results.putIfAbsent(it.id, it) }
            }
        }

        // WALK/METRO/MCC -> BUS/TRAM.
        for (exit in exits) {
            val first = rail.findFastest(
                origin = origin,
                destination = exit.point,
                departureEpochSec = departureEpochSec,
                originName = "Откуда",
                destinationName = exit.name
            ) ?: continue
            val second = surfaceStage(
                exit.point,
                destination,
                first.arrivalEpochSec,
                exit.name,
                "Куда"
            ) ?: continue
            if (!usesSurfaceTransit(second)) continue
            combine(first, second)?.let { results.putIfAbsent(it.id, it) }
        }

        // BUS/TRAM -> METRO/MCC -> BUS/TRAM. Evaluate the cheap rail middle first and only run
        // expensive surface scans for the best few complete-looking beams.
        data class Beam(
            val entry: RailWaypointIndex.Waypoint,
            val exit: RailWaypointIndex.Waypoint,
            val first: RouteCandidate,
            val middle: RouteCandidate,
            val heuristicArrival: Long
        )

        val beams = ArrayList<Beam>()
        for ((entry, first) in firstSurface) {
            for (exit in exits) {
                if (entry.id == exit.id) continue
                val middle = rail.findFastest(
                    origin = entry.point,
                    destination = exit.point,
                    departureEpochSec = first.arrivalEpochSec,
                    originName = entry.name,
                    destinationName = exit.name
                ) ?: continue
                if (!usesRail(middle)) continue
                val finalWalk = estimatedWalkSeconds(exit.point, destination)
                beams += Beam(entry, exit, first, middle, middle.arrivalEpochSec + finalWalk)
            }
        }

        val finalBeamCount = if (broadSearch) BROAD_FINAL_BEAMS else FAST_FINAL_BEAMS
        for (beamItem in beams.sortedBy { it.heuristicArrival }.take(finalBeamCount)) {
            val last = surfaceStage(
                beamItem.exit.point,
                destination,
                beamItem.middle.arrivalEpochSec,
                beamItem.exit.name,
                "Куда"
            ) ?: continue
            if (!usesSurfaceTransit(last)) continue
            combine(beamItem.first, beamItem.middle, last)?.let { results.putIfAbsent(it.id, it) }
        }

        return results.values
            .filter { usesSurfaceTransit(it) && usesRail(it) }
            .sortedBy { it.arrivalEpochSec }
            .take(MAX_RETURNED)
    }

    private fun surfaceStage(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long,
        originName: String,
        destinationName: String
    ): RouteCandidate? {
        val serviceSecLong = departureEpochSec - serviceMidnightEpochSec
        if (serviceSecLong !in 0L..MAX_SERVICE_SECONDS.toLong()) return null
        return surface.findFastest(
            origin = origin,
            destination = destination,
            departureServiceSec = serviceSecLong.toInt(),
            serviceMidnightEpochSec = serviceMidnightEpochSec,
            originName = originName,
            destinationName = destinationName,
            horizonSeconds = MIXED_SURFACE_HORIZON_SECONDS
        ).fastest
    }

    private fun combine(vararg stages: RouteCandidate): RouteCandidate? {
        if (stages.isEmpty()) return null
        val legs = ArrayList<RouteLeg>()
        var cursor = stages.first().requestedDepartureEpochSec
        for (stage in stages) {
            if (stage.legs.isEmpty()) return null
            if (stage.legs.first().departureEpochSec + MAX_STAGE_CLOCK_SKEW_SECONDS < cursor) return null
            for (leg in stage.legs) {
                if (leg.arrivalEpochSec < cursor - MAX_STAGE_CLOCK_SKEW_SECONDS) return null
                legs += leg
                cursor = maxOf(cursor, leg.arrivalEpochSec)
            }
        }
        if (legs.isEmpty()) return null
        val signature = legs.joinToString("|") {
            "${it.mode}:${it.lineId ?: "walk"}:${it.from.id}:${it.to.id}"
        }
        return RouteCandidate(
            id = "mixed-${signature.hashCode().toUInt().toString(16)}",
            requestedDepartureEpochSec = stages.first().requestedDepartureEpochSec,
            legs = legs
        )
    }

    private fun usesSurfaceTransit(route: RouteCandidate): Boolean =
        route.legs.any { it.mode == TransportMode.BUS || it.mode == TransportMode.TRAM }

    private fun usesRail(route: RouteCandidate): Boolean =
        route.legs.any { it.mode in RAIL_MODES }

    private fun estimatedWalkSeconds(from: GeoPoint, to: GeoPoint): Int {
        val meters = haversineMeters(from, to) * WALK_DETOUR_FACTOR
        return ceil(meters / WALK_SPEED_MPS).toInt()
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val q = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(q)))
    }

    companion object {
        private const val MAX_SERVICE_SECONDS = 30 * 60 * 60
        private const val MIXED_SURFACE_HORIZON_SECONDS = 3 * 60 * 60
        private const val ENTRY_MAX_METERS = 8_000
        private const val EXIT_MAX_METERS = 8_000
        private const val FAST_BEAM = 2
        private const val BROAD_BEAM = 4
        private const val FAST_FINAL_BEAMS = 1
        private const val BROAD_FINAL_BEAMS = 3
        private const val MAX_RETURNED = 8
        private const val MAX_STAGE_CLOCK_SKEW_SECONDS = 2L
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val WALK_DETOUR_FACTOR = 1.20
        private const val WALK_SPEED_MPS = 1.35
        private val RAIL_MODES = setOf(TransportMode.METRO, TransportMode.MCC)
    }
}

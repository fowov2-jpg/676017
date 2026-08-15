package app.humanrouter.routing

import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adds rail-to-rail interchanges whose station names differ (for example METRO <-> MCC).
 * The transfer itself is a real OSM walking-graph path when available; no teleport edge is used.
 */
internal class RailExternalTransferComposer private constructor(
    private val rail: RailGraphRouter,
    private val walkGraph: RuntimeWalkGraph?,
    private val preferences: RoutePreferences,
    graphFile: File
) {
    private data class Stop(
        val id: String,
        val name: String,
        val point: GeoPoint,
        val lineKeys: MutableSet<String> = LinkedHashSet()
    )

    private data class TransferPair(
        val a: Stop,
        val b: Stop,
        val geometricMeters: Int
    )

    private val transferPairs: List<TransferPair> = loadPairs(JSONObject(graphFile.readText()))

    fun findCandidates(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long,
        broadSearch: Boolean
    ): List<RouteCandidate> {
        if (transferPairs.isEmpty()) return emptyList()
        val pairLimit = if (broadSearch) BROAD_PAIR_LIMIT else FAST_PAIR_LIMIT
        val oriented = ArrayList<Pair<TransferPair, Boolean>>(transferPairs.size * 2)
        for (pair in transferPairs) {
            oriented += pair to false
            oriented += pair to true
        }

        val chosen = oriented
            .sortedBy { (pair, reverse) ->
                val from = if (reverse) pair.b else pair.a
                val to = if (reverse) pair.a else pair.b
                haversineMeters(origin, from.point) + haversineMeters(to.point, destination)
            }
            .take(pairLimit)

        val result = LinkedHashMap<String, RouteCandidate>()
        for ((pair, reverse) in chosen) {
            val from = if (reverse) pair.b else pair.a
            val to = if (reverse) pair.a else pair.b
            val first = rail.findFastest(
                origin = origin,
                destination = from.point,
                departureEpochSec = departureEpochSec,
                originName = "Откуда",
                destinationName = from.name
            ) ?: continue
            if (!usesRail(first)) continue

            val walk = transferWalk(from, to, pair.geometricMeters) ?: continue
            val walkDeparture = first.arrivalEpochSec
            val walkArrival = walkDeparture + walk.seconds
            val second = rail.findFastest(
                origin = to.point,
                destination = destination,
                departureEpochSec = walkArrival,
                originName = to.name,
                destinationName = "Куда"
            ) ?: continue
            if (!usesRail(second)) continue

            val transferLeg = RouteLeg(
                mode = TransportMode.WALK,
                from = RoutePlace(from.id, from.name, from.point),
                to = RoutePlace(to.id, to.name, to.point),
                departureEpochSec = walkDeparture,
                arrivalEpochSec = walkArrival,
                walkMeters = walk.meters,
                uncertaintySeconds = if (walkGraph != null) 30 else 90,
                realtimeConfidence = if (walkGraph != null) 0.94 else 0.68,
                geometry = walk.geometry
            )
            combine(first, transferLeg, second)?.let { candidate ->
                result.putIfAbsent(candidate.id, candidate)
            }
        }
        return result.values.sortedBy { it.arrivalEpochSec }.take(MAX_RETURNED)
    }

    private fun transferWalk(from: Stop, to: Stop, geometricMeters: Int): RuntimeWalkGraph.WalkCost? {
        if (walkGraph != null) {
            return walkGraph.shortestWalk(
                from = from.point,
                to = to.point,
                maxSeconds = TRANSFER_MAX_SECONDS,
                maxMeters = TRANSFER_MAX_WALK_METERS
            )
        }

        val meters = ceil(geometricMeters * WALK_DETOUR_FACTOR).toInt()
        if (meters > TRANSFER_MAX_WALK_METERS) return null
        val seconds = ceil(meters / preferences.walkingSpeedMetersPerSecond).toInt()
        return RuntimeWalkGraph.WalkCost(seconds, meters)
    }

    private fun combine(
        first: RouteCandidate,
        transfer: RouteLeg,
        second: RouteCandidate
    ): RouteCandidate? {
        if (first.arrivalEpochSec > transfer.departureEpochSec + CLOCK_SKEW_SECONDS) return null
        if (second.requestedDepartureEpochSec + CLOCK_SKEW_SECONDS < transfer.arrivalEpochSec) return null
        val legs = ArrayList<RouteLeg>(first.legs.size + second.legs.size + 1)
        legs += first.legs
        legs += transfer
        legs += second.legs
        val signature = legs.joinToString("|") {
            "${it.mode}:${it.lineId ?: "walk"}:${it.from.id}:${it.to.id}"
        }
        return RouteCandidate(
            id = "rail-xfer-${signature.hashCode().toUInt().toString(16)}",
            requestedDepartureEpochSec = first.requestedDepartureEpochSec,
            legs = legs
        )
    }

    private fun usesRail(route: RouteCandidate): Boolean =
        route.legs.any { it.mode in ROUTEABLE_RAIL_MODES }

    private fun loadPairs(root: JSONObject): List<TransferPair> {
        val stops = LinkedHashMap<String, Stop>()
        val routes = root.getJSONArray("routes")
        for (routeIndex in 0 until routes.length()) {
            val route = routes.getJSONObject(routeIndex)
            if (!route.optBoolean("routeable", false)) continue
            val mode = TransportMode.fromRuntimeValue(route.optString("mode")) ?: continue
            if (mode !in ROUTEABLE_RAIL_MODES) continue
            val relationId = route.getString("osm_relation_id")
            val lineKey = "${mode.name}:$relationId"
            val routeStops = route.getJSONArray("stops")
            for (i in 0 until routeStops.length()) {
                val item = routeStops.getJSONObject(i)
                val id = item.getString("osm_stop_id")
                val stop = stops.getOrPut(id) {
                    Stop(
                        id = "rail:$id",
                        name = item.optString("name", id),
                        point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                    )
                }
                stop.lineKeys += lineKey
            }
        }

        val list = stops.values.toList()
        val pairs = ArrayList<TransferPair>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (normalizeName(a.name) == normalizeName(b.name)) continue
                if (a.lineKeys.any { it in b.lineKeys }) continue
                val distance = haversineMeters(a.point, b.point).toInt()
                if (distance in MIN_TRANSFER_DISTANCE_METERS..MAX_TRANSFER_GEOMETRIC_METERS) {
                    pairs += TransferPair(a, b, distance)
                }
            }
        }
        return pairs
    }

    private fun normalizeName(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9]+"), "")

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val q = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(q)))
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MIN_TRANSFER_DISTANCE_METERS = 35
        private const val MAX_TRANSFER_GEOMETRIC_METERS = 450
        private const val TRANSFER_MAX_WALK_METERS = 900
        private const val TRANSFER_MAX_SECONDS = 15 * 60
        private const val WALK_DETOUR_FACTOR = 1.25
        private const val FAST_PAIR_LIMIT = 4
        private const val BROAD_PAIR_LIMIT = 12
        private const val MAX_RETURNED = 4
        private const val CLOCK_SKEW_SECONDS = 2L
        private val ROUTEABLE_RAIL_MODES = setOf(
            TransportMode.METRO,
            TransportMode.MCC
        )

        fun openOrNull(
            runtimeRoot: File,
            rail: RailGraphRouter?,
            walkGraph: RuntimeWalkGraph?,
            preferences: RoutePreferences
        ): RailExternalTransferComposer? {
            if (rail == null) return null
            val graph = File(runtimeRoot, "rail/graph.json")
            if (!graph.exists()) return null
            return runCatching { RailExternalTransferComposer(rail, walkGraph, preferences, graph) }.getOrNull()
        }
    }
}

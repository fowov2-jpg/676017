package app.humanrouter.routing

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Connection Scan Algorithm over the official Moscow BUS/TRAM timetable.
 * Walking access, egress and stop-to-stop transfers use the installed OSM walking graph when
 * available. The predecessor chain is immutable so a later, faster label cannot corrupt the path
 * that was used to board an already-reachable trip.
 */
internal class SurfaceCsaRouter(
    private val repository: SurfaceScheduleRepository,
    private val preferences: RoutePreferences = RoutePreferences(),
    private val walkGraph: RuntimeWalkGraph? = null
) {
    private val stops = repository.loadStops()
    private val routes = repository.loadRoutes()
    private val stopIndexById = HashMap<Int, Int>(stops.size * 2)
    private val grid = HashMap<Long, MutableList<Int>>()
    private val transferCache = arrayOfNulls<List<WalkLink>>(stops.size)

    init {
        stops.forEachIndexed { index, stop ->
            stopIndexById[stop.id] = index
            grid.getOrPut(gridKey(stop.lat, stop.lon)) { ArrayList() }.add(index)
        }
    }

    data class Result(
        val fastest: RouteCandidate?,
        val serviceDate: String,
        val usedTransit: Boolean,
        val usedOsmWalkingGraph: Boolean
    )

    fun findFastest(
        origin: GeoPoint,
        destination: GeoPoint,
        departureServiceSec: Int,
        serviceMidnightEpochSec: Long,
        originName: String = "Откуда",
        destinationName: String = "Куда",
        horizonSeconds: Int = 4 * 60 * 60
    ): Result {
        val originPlace = RoutePlace("origin", originName, origin)
        val destinationPlace = RoutePlace("destination", destinationName, destination)

        val geometricDirectMeters = walkingMeters(origin, destination)
        val exactDirect = if (geometricDirectMeters <= DIRECT_GRAPH_SEARCH_LIMIT_METERS) {
            walkGraph?.shortestWalk(
                from = origin,
                to = destination,
                maxSeconds = DIRECT_GRAPH_SEARCH_LIMIT_SECONDS,
                maxMeters = DIRECT_GRAPH_SEARCH_LIMIT_METERS
            )
        } else {
            null
        }
        val directCost = if (walkGraph != null) {
            exactDirect
        } else {
            RuntimeWalkGraph.WalkCost(
                seconds = walkingSeconds(geometricDirectMeters),
                meters = geometricDirectMeters
            )
        }

        var bestArrivalSec = directCost?.let { departureServiceSec + it.seconds } ?: INF
        var bestStopIndex = -1

        val earliest = IntArray(stops.size) { INF }
        val paths = arrayOfNulls<PathNode>(stops.size)

        for (link in accessLinks(origin)) {
            val arrival = departureServiceSec + link.seconds
            if (arrival < earliest[link.stopIndex]) {
                earliest[link.stopIndex] = arrival
                paths[link.stopIndex] = PathNode(
                    parent = null,
                    step = RawStep.Walk(
                        from = originPlace,
                        to = stops[link.stopIndex].place(),
                        departureSec = departureServiceSec,
                        arrivalSec = arrival,
                        meters = link.meters
                    )
                )
            }
        }

        val egressByStop = HashMap<Int, WalkLink>()
        for (link in egressLinks(destination)) {
            egressByStop[link.stopIndex] = link
            val accessArrival = earliest[link.stopIndex]
            if (accessArrival != INF) {
                val candidate = accessArrival + link.seconds
                if (candidate < bestArrivalSec) {
                    bestArrivalSec = candidate
                    bestStopIndex = link.stopIndex
                }
            }
        }

        val tripStates = HashMap<String, TripState>(4_096)
        val scanEnd = min(departureServiceSec + horizonSeconds, MAX_SERVICE_SEC)

        repository.scanConnections(departureServiceSec, scanEnd) { connection ->
            if (connection.departureSec > bestArrivalSec) return@scanConnections false

            val fromIndex = stopIndexById[connection.fromStopId] ?: return@scanConnections true
            val toIndex = stopIndexById[connection.toStopId] ?: return@scanConnections true

            val existingState = tripStates[connection.tripId]
            val state = if (existingState != null) {
                if (connection.sequence < existingState.boardingSequence) {
                    return@scanConnections true
                }
                existingState
            } else {
                val knownArrival = earliest[fromIndex]
                val boardingPath = paths[fromIndex] ?: return@scanConnections true
                if (knownArrival == INF ||
                    knownArrival + boardBufferSeconds(boardingPath) > connection.departureSec
                ) {
                    return@scanConnections true
                }

                TripState(
                    boardingPath = boardingPath,
                    boardingStopIndex = fromIndex,
                    firstConnection = connection,
                    boardingSequence = connection.sequence
                ).also { tripStates[connection.tripId] = it }
            }

            if (connection.arrivalSec < earliest[toIndex]) {
                val ridePath = PathNode(
                    parent = state.boardingPath,
                    step = RawStep.Ride(
                        from = stops[state.boardingStopIndex].place(),
                        to = stops[toIndex].place(),
                        firstConnection = state.firstConnection,
                        lastConnection = connection
                    )
                )

                earliest[toIndex] = connection.arrivalSec
                paths[toIndex] = ridePath

                egressByStop[toIndex]?.let { egress ->
                    val candidate = connection.arrivalSec + egress.seconds
                    if (candidate < bestArrivalSec) {
                        bestArrivalSec = candidate
                        bestStopIndex = toIndex
                    }
                }

                for (transfer in transferNeighbors(toIndex)) {
                    val transferArrival = connection.arrivalSec + transfer.seconds
                    if (transferArrival < earliest[transfer.stopIndex]) {
                        val transferPath = PathNode(
                            parent = ridePath,
                            step = RawStep.Walk(
                                from = stops[toIndex].place(),
                                to = stops[transfer.stopIndex].place(),
                                departureSec = connection.arrivalSec,
                                arrivalSec = transferArrival,
                                meters = transfer.meters
                            )
                        )
                        earliest[transfer.stopIndex] = transferArrival
                        paths[transfer.stopIndex] = transferPath

                        egressByStop[transfer.stopIndex]?.let { egress ->
                            val candidate = transferArrival + egress.seconds
                            if (candidate < bestArrivalSec) {
                                bestArrivalSec = candidate
                                bestStopIndex = transfer.stopIndex
                            }
                        }
                    }
                }
            }
            true
        }

        if (bestStopIndex < 0) {
            return Result(
                fastest = directCost?.let { walk -> RouteCandidate(
                    id = "walk-$departureServiceSec",
                    requestedDepartureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                    legs = listOf(
                        RouteLeg(
                            mode = TransportMode.WALK,
                            from = originPlace,
                            to = destinationPlace,
                            departureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                            arrivalEpochSec = serviceMidnightEpochSec + departureServiceSec + walk.seconds,
                            walkMeters = walk.meters,
                            uncertaintySeconds = if (exactDirect != null) 15 else 90,
                            realtimeConfidence = if (exactDirect != null) 0.98 else 0.70
                        )
                    )
                ) },
                serviceDate = repository.serviceDate,
                usedTransit = false,
                usedOsmWalkingGraph = walkGraph != null
            )
        }

        val terminalPath = paths[bestStopIndex]
            ?: return Result(
                fastest = directCost?.let { walk -> RouteCandidate(
                    id = "walk-$departureServiceSec",
                    requestedDepartureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                    legs = listOf(
                        RouteLeg(
                            mode = TransportMode.WALK,
                            from = originPlace,
                            to = destinationPlace,
                            departureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                            arrivalEpochSec = serviceMidnightEpochSec + departureServiceSec + walk.seconds,
                            walkMeters = walk.meters,
                            uncertaintySeconds = if (exactDirect != null) 15 else 90,
                            realtimeConfidence = if (exactDirect != null) 0.98 else 0.70
                        )
                    )
                ) },
                serviceDate = repository.serviceDate,
                usedTransit = false,
                usedOsmWalkingGraph = walkGraph != null
            )

        val rawSteps = collectPath(
            terminalPath = terminalPath,
            terminalStopIndex = bestStopIndex,
            destination = destinationPlace,
            egress = egressByStop[bestStopIndex]
        )
        val legs = toRouteLegs(rawSteps, serviceMidnightEpochSec)

        return Result(
            fastest = RouteCandidate(
                id = buildRouteId(legs),
                requestedDepartureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                legs = legs
            ),
            serviceDate = repository.serviceDate,
            usedTransit = legs.any { it.mode != TransportMode.WALK },
            usedOsmWalkingGraph = walkGraph != null
        )
    }

    private fun accessLinks(point: GeoPoint): List<WalkLink> {
        if (walkGraph != null) {
            return walkGraph.stopCostsFrom(
                point = point,
                maxSeconds = ACCESS_MAX_SECONDS,
                maxMeters = ACCESS_RADIUS_METERS,
                limit = MAX_ACCESS_STOPS
            ).orEmpty().entries.mapNotNull { (stopId, cost) ->
                stopIndexById[stopId]?.let { WalkLink(it, cost.meters, cost.seconds) }
            }.sortedBy { it.seconds }.take(MAX_ACCESS_STOPS)
        }
        return nearbyStops(point, ACCESS_RADIUS_METERS, MAX_ACCESS_STOPS)
    }

    private fun egressLinks(point: GeoPoint): List<WalkLink> {
        if (walkGraph != null) {
            return walkGraph.stopCostsTo(
                point = point,
                maxSeconds = EGRESS_MAX_SECONDS,
                maxMeters = EGRESS_RADIUS_METERS,
                limit = MAX_EGRESS_STOPS
            ).orEmpty().entries.mapNotNull { (stopId, cost) ->
                stopIndexById[stopId]?.let { WalkLink(it, cost.meters, cost.seconds) }
            }.sortedBy { it.seconds }.take(MAX_EGRESS_STOPS)
        }
        return nearbyStops(point, EGRESS_RADIUS_METERS, MAX_EGRESS_STOPS)
    }

    private fun transferNeighbors(stopIndex: Int): List<WalkLink> {
        transferCache[stopIndex]?.let { return it }

        val stop = stops[stopIndex]
        val links = if (walkGraph != null) {
            walkGraph.stopCostsFromStop(
                stopId = stop.id,
                maxSeconds = TRANSFER_MAX_SECONDS,
                maxMeters = TRANSFER_MAX_METERS,
                limit = MAX_TRANSFER_NEIGHBORS
            ).orEmpty().entries.mapNotNull { (stopId, cost) ->
                val index = stopIndexById[stopId] ?: return@mapNotNull null
                if (index == stopIndex) null else WalkLink(index, cost.meters, cost.seconds)
            }.sortedBy { it.seconds }.take(MAX_TRANSFER_NEIGHBORS)
        } else {
            nearbyStops(
                point = GeoPoint(stop.lat, stop.lon),
                maxMeters = TRANSFER_GEOMETRY_FALLBACK_METERS,
                limit = MAX_TRANSFER_NEIGHBORS + 1
            ).filter { it.stopIndex != stopIndex }.take(MAX_TRANSFER_NEIGHBORS)
        }

        transferCache[stopIndex] = links
        return links
    }

    private fun collectPath(
        terminalPath: PathNode,
        terminalStopIndex: Int,
        destination: RoutePlace,
        egress: WalkLink?
    ): List<RawStep> {
        val reversed = ArrayList<RawStep>()
        var node: PathNode? = terminalPath
        while (node != null) {
            reversed += node.step
            node = node.parent
        }
        reversed.reverse()

        if (egress != null && egress.seconds > 0) {
            val startSec = when (val last = reversed.lastOrNull()) {
                is RawStep.Walk -> last.arrivalSec
                is RawStep.Ride -> last.lastConnection.arrivalSec
                null -> 0
            }
            reversed += RawStep.Walk(
                from = stops[terminalStopIndex].place(),
                to = destination,
                departureSec = startSec,
                arrivalSec = startSec + egress.seconds,
                meters = egress.meters
            )
        }
        return reversed
    }

    private fun toRouteLegs(
        steps: List<RawStep>,
        serviceMidnightEpochSec: Long
    ): List<RouteLeg> {
        val result = ArrayList<RouteLeg>(steps.size)
        var previousTransitArrival: Int? = null
        var walkingSinceTransitSeconds = 0

        fun epoch(serviceSec: Int): Long = serviceMidnightEpochSec + serviceSec

        for (step in steps) {
            when (step) {
                is RawStep.Walk -> {
                    val duration = (step.arrivalSec - step.departureSec).coerceAtLeast(0)
                    if (previousTransitArrival != null) {
                        walkingSinceTransitSeconds += duration
                    }
                    result += RouteLeg(
                        mode = TransportMode.WALK,
                        from = step.from,
                        to = step.to,
                        departureEpochSec = epoch(step.departureSec),
                        arrivalEpochSec = epoch(step.arrivalSec),
                        walkMeters = step.meters,
                        uncertaintySeconds = if (walkGraph != null) 20 else 75,
                        realtimeConfidence = if (walkGraph != null) 0.97 else 0.72
                    )
                }

                is RawStep.Ride -> {
                    val first = step.firstConnection
                    val last = step.lastConnection
                    val route = routes[first.routeId] ?: continue
                    val mode = route.mode
                    val duration = (last.arrivalSec - first.departureSec).coerceAtLeast(0)
                    val transferBuffer = previousTransitArrival?.let { previousArrival ->
                        (
                            first.departureSec -
                                previousArrival -
                                walkingSinceTransitSeconds
                            ).coerceAtLeast(0)
                    } ?: 0

                    result += RouteLeg(
                        mode = mode,
                        from = step.from,
                        to = step.to,
                        departureEpochSec = epoch(first.departureSec),
                        arrivalEpochSec = epoch(last.arrivalSec),
                        lineId = route.id,
                        lineName = route.shortName ?: route.longName,
                        uncertaintySeconds = (duration * 0.15).toInt().coerceIn(60, 300),
                        realtimeConfidence = 0.45,
                        transferBufferSeconds = transferBuffer,
                        stopCount = (last.sequence - first.sequence + 1).coerceAtLeast(1)
                    )
                    previousTransitArrival = last.arrivalSec
                    walkingSinceTransitSeconds = 0
                }
            }
        }
        return result
    }

    private fun buildRouteId(legs: List<RouteLeg>): String {
        val signature = legs.joinToString("|") { leg ->
            "${leg.mode}:${leg.lineId ?: "walk"}:${leg.from.id}:${leg.to.id}"
        }
        return "surface-${signature.hashCode().toUInt().toString(16)}"
    }

    private fun SurfaceStop.place(): RoutePlace = RoutePlace(
        id = "stop:$id",
        name = name,
        point = GeoPoint(lat, lon)
    )

    private fun boardBufferSeconds(path: PathNode): Int = when {
        path.step is RawStep.Ride -> SAME_STOP_TRANSFER_BUFFER_SECONDS
        path.parent == null -> ACCESS_BOARD_BUFFER_SECONDS
        else -> WALK_TRANSFER_BOARD_BUFFER_SECONDS
    }

    private fun nearbyStops(point: GeoPoint, maxMeters: Int, limit: Int): List<WalkLink> {
        val radiusCells = ceil(maxMeters / APPROX_CELL_METERS).toInt() + 1
        val baseX = gridX(point.lat)
        val baseY = gridY(point.lon)
        val candidates = ArrayList<WalkLink>()

        for (dx in -radiusCells..radiusCells) {
            for (dy in -radiusCells..radiusCells) {
                val indices = grid[packGrid(baseX + dx, baseY + dy)] ?: continue
                for (index in indices) {
                    val stop = stops[index]
                    val meters = haversineMeters(point.lat, point.lon, stop.lat, stop.lon)
                    if (meters <= maxMeters) {
                        val conservativeMeters = (meters * WALK_DETOUR_FACTOR)
                            .toInt()
                            .coerceAtLeast(1)
                        candidates += WalkLink(
                            stopIndex = index,
                            meters = conservativeMeters,
                            seconds = walkingSeconds(conservativeMeters)
                        )
                    }
                }
            }
        }
        return candidates.sortedBy { it.seconds }.take(limit)
    }

    private fun walkingMeters(from: GeoPoint, to: GeoPoint): Int =
        (haversineMeters(from.lat, from.lon, to.lat, to.lon) * WALK_DETOUR_FACTOR)
            .toInt()
            .coerceAtLeast(1)

    private fun walkingSeconds(meters: Int): Int =
        max(1, (meters / preferences.walkingSpeedMetersPerSecond).toInt())

    private fun gridKey(lat: Double, lon: Double): Long = packGrid(gridX(lat), gridY(lon))
    private fun gridX(lat: Double): Int = floor((lat + 90.0) / GRID_DEGREES).toInt()
    private fun gridY(lon: Double): Int = floor((lon + 180.0) / GRID_DEGREES).toInt()

    private fun packGrid(x: Int, y: Int): Long =
        (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180.0
        val p2 = lat2 * PI / 180.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    private data class PathNode(
        val parent: PathNode?,
        val step: RawStep
    )

    private data class TripState(
        val boardingPath: PathNode,
        val boardingStopIndex: Int,
        val firstConnection: SurfaceConnection,
        val boardingSequence: Int
    )

    private sealed interface RawStep {
        data class Walk(
            val from: RoutePlace,
            val to: RoutePlace,
            val departureSec: Int,
            val arrivalSec: Int,
            val meters: Int
        ) : RawStep

        data class Ride(
            val from: RoutePlace,
            val to: RoutePlace,
            val firstConnection: SurfaceConnection,
            val lastConnection: SurfaceConnection
        ) : RawStep
    }

    private data class WalkLink(
        val stopIndex: Int,
        val meters: Int,
        val seconds: Int
    )

    companion object {
        private const val INF = Int.MAX_VALUE / 4
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val GRID_DEGREES = 0.005
        private const val APPROX_CELL_METERS = 550.0
        private const val WALK_DETOUR_FACTOR = 1.22

        private const val ACCESS_RADIUS_METERS = 1_600
        private const val EGRESS_RADIUS_METERS = 1_600
        private const val ACCESS_MAX_SECONDS = 25 * 60
        private const val EGRESS_MAX_SECONDS = 25 * 60
        private const val MAX_ACCESS_STOPS = 48
        private const val MAX_EGRESS_STOPS = 48

        private const val TRANSFER_MAX_METERS = 700
        private const val TRANSFER_MAX_SECONDS = 12 * 60
        private const val TRANSFER_GEOMETRY_FALLBACK_METERS = 500
        private const val MAX_TRANSFER_NEIGHBORS = 12

        private const val ACCESS_BOARD_BUFFER_SECONDS = 20
        private const val WALK_TRANSFER_BOARD_BUFFER_SECONDS = 30
        private const val SAME_STOP_TRANSFER_BUFFER_SECONDS = 45

        private const val DIRECT_GRAPH_SEARCH_LIMIT_METERS = 6_000
        private const val DIRECT_GRAPH_SEARCH_LIMIT_SECONDS = 90 * 60
        private const val MAX_SERVICE_SEC = 30 * 60 * 60
    }
}

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
 * First production routing core for the data that is already trustworthy in the runtime:
 * WALK + official BUS/TRAM schedule. It uses a Connection Scan Algorithm over the timetable
 * connections and allows short walking transfers between nearby surface stops.
 *
 * The stop-to-stop transfer walk is deliberately conservative (great-circle distance with a
 * detour factor) until it is replaced by the installed OSM walk graph. The timetable itself is
 * exact for the runtime service date.
 */
internal class SurfaceCsaRouter(
    private val repository: SurfaceScheduleRepository,
    private val preferences: RoutePreferences = RoutePreferences()
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
        val fastest: RouteCandidate,
        val serviceDate: String,
        val usedTransit: Boolean
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

        val directMeters = walkingMeters(origin, destination)
        val directWalkSeconds = walkingSeconds(directMeters)
        var bestArrivalSec = departureServiceSec + directWalkSeconds
        var bestStopIndex = -1

        val earliest = IntArray(stops.size) { INF }
        val previous = arrayOfNulls<Previous>(stops.size)

        for (link in nearbyStops(origin, ACCESS_RADIUS_METERS, MAX_ACCESS_STOPS)) {
            val arrival = departureServiceSec + link.seconds
            if (arrival < earliest[link.stopIndex]) {
                earliest[link.stopIndex] = arrival
                previous[link.stopIndex] = Previous.Access(
                    departureSec = departureServiceSec,
                    arrivalSec = arrival,
                    meters = link.meters
                )
            }
        }

        val egressByStop = HashMap<Int, WalkLink>()
        for (link in nearbyStops(destination, EGRESS_RADIUS_METERS, MAX_EGRESS_STOPS)) {
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

        val boardedTrips = HashSet<String>(4_096)
        val scanEnd = min(departureServiceSec + horizonSeconds, MAX_SERVICE_SEC)

        repository.scanConnections(departureServiceSec, scanEnd) { connection ->
            if (connection.departureSec > bestArrivalSec) return@scanConnections false

            val fromIndex = stopIndexById[connection.fromStopId] ?: return@scanConnections true
            val toIndex = stopIndexById[connection.toStopId] ?: return@scanConnections true
            val alreadyOnTrip = boardedTrips.contains(connection.tripId)
            val ready = if (alreadyOnTrip) {
                true
            } else {
                val knownArrival = earliest[fromIndex]
                knownArrival != INF &&
                    knownArrival + boardBufferSeconds(previous[fromIndex]) <= connection.departureSec
            }

            if (!ready) return@scanConnections true
            boardedTrips.add(connection.tripId)

            if (connection.arrivalSec < earliest[toIndex]) {
                earliest[toIndex] = connection.arrivalSec
                previous[toIndex] = Previous.Ride(fromIndex, connection)
                egressByStop[toIndex]?.let { egress ->
                    val candidate = connection.arrivalSec + egress.seconds
                    if (candidate < bestArrivalSec) {
                        bestArrivalSec = candidate
                        bestStopIndex = toIndex
                    }
                }

                // One explicit street transfer is enough between two transit legs. Access walking
                // already seeds all nearby origin stops, so we do not recursively chain footpaths.
                for (transfer in transferNeighbors(toIndex)) {
                    val transferArrival = connection.arrivalSec + transfer.seconds
                    if (transferArrival < earliest[transfer.stopIndex]) {
                        earliest[transfer.stopIndex] = transferArrival
                        previous[transfer.stopIndex] = Previous.Transfer(
                            fromIndex = toIndex,
                            departureSec = connection.arrivalSec,
                            arrivalSec = transferArrival,
                            meters = transfer.meters
                        )
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
                fastest = RouteCandidate(
                    id = "walk-$departureServiceSec",
                    requestedDepartureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                    legs = listOf(
                        RouteLeg(
                            mode = TransportMode.WALK,
                            from = originPlace,
                            to = destinationPlace,
                            departureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                            arrivalEpochSec = serviceMidnightEpochSec + departureServiceSec + directWalkSeconds,
                            walkMeters = directMeters,
                            uncertaintySeconds = 30,
                            realtimeConfidence = 0.95
                        )
                    )
                ),
                serviceDate = repository.serviceDate,
                usedTransit = false
            )
        }

        val rawSteps = reconstruct(
            terminalStopIndex = bestStopIndex,
            previous = previous,
            origin = originPlace,
            destination = destinationPlace,
            egress = egressByStop[bestStopIndex]
        )
        val legs = mergeRideSteps(rawSteps, serviceMidnightEpochSec)

        return Result(
            fastest = RouteCandidate(
                id = buildRouteId(legs),
                requestedDepartureEpochSec = serviceMidnightEpochSec + departureServiceSec,
                legs = legs
            ),
            serviceDate = repository.serviceDate,
            usedTransit = legs.any { it.mode != TransportMode.WALK }
        )
    }

    private fun reconstruct(
        terminalStopIndex: Int,
        previous: Array<Previous?>,
        origin: RoutePlace,
        destination: RoutePlace,
        egress: WalkLink?
    ): List<RawStep> {
        val reversed = ArrayList<RawStep>()
        var index = terminalStopIndex
        while (true) {
            when (val prev = previous[index]) {
                is Previous.Access -> {
                    reversed += RawStep.Walk(
                        from = origin,
                        to = stops[index].place(),
                        departureSec = prev.departureSec,
                        arrivalSec = prev.arrivalSec,
                        meters = prev.meters
                    )
                    break
                }
                is Previous.Ride -> {
                    reversed += RawStep.Ride(
                        from = stops[prev.fromIndex].place(),
                        to = stops[index].place(),
                        connection = prev.connection
                    )
                    index = prev.fromIndex
                }
                is Previous.Transfer -> {
                    reversed += RawStep.Walk(
                        from = stops[prev.fromIndex].place(),
                        to = stops[index].place(),
                        departureSec = prev.departureSec,
                        arrivalSec = prev.arrivalSec,
                        meters = prev.meters
                    )
                    index = prev.fromIndex
                }
                null -> break
            }
        }
        reversed.reverse()

        if (egress != null && egress.seconds > 0) {
            val startSec = when (val last = reversed.lastOrNull()) {
                is RawStep.Walk -> last.arrivalSec
                is RawStep.Ride -> last.connection.arrivalSec
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

    private fun mergeRideSteps(
        steps: List<RawStep>,
        serviceMidnightEpochSec: Long
    ): List<RouteLeg> {
        val result = ArrayList<RouteLeg>()
        var index = 0
        var previousTransitArrival: Int? = null
        fun epoch(serviceSec: Int): Long = serviceMidnightEpochSec + serviceSec

        while (index < steps.size) {
            when (val step = steps[index]) {
                is RawStep.Walk -> {
                    result += RouteLeg(
                        mode = TransportMode.WALK,
                        from = step.from,
                        to = step.to,
                        departureEpochSec = epoch(step.departureSec),
                        arrivalEpochSec = epoch(step.arrivalSec),
                        walkMeters = step.meters,
                        uncertaintySeconds = 30,
                        realtimeConfidence = 0.95
                    )
                    index++
                }
                is RawStep.Ride -> {
                    val first = step
                    var last = step
                    var cursor = index + 1
                    while (cursor < steps.size) {
                        val next = steps[cursor]
                        if (next !is RawStep.Ride || next.connection.tripId != first.connection.tripId) break
                        last = next
                        cursor++
                    }
                    val route = routes[first.connection.routeId]
                    val mode = route?.mode ?: TransportMode.BUS
                    val duration = (last.connection.arrivalSec - first.connection.departureSec).coerceAtLeast(0)
                    val transferBuffer = previousTransitArrival?.let {
                        (first.connection.departureSec - it).coerceAtLeast(0)
                    } ?: 0
                    result += RouteLeg(
                        mode = mode,
                        from = first.from,
                        to = last.to,
                        departureEpochSec = epoch(first.connection.departureSec),
                        arrivalEpochSec = epoch(last.connection.arrivalSec),
                        lineId = route?.id ?: first.connection.routeId,
                        lineName = route?.shortName ?: route?.longName,
                        uncertaintySeconds = (duration * 0.15).toInt().coerceIn(60, 300),
                        realtimeConfidence = 0.45,
                        transferBufferSeconds = transferBuffer
                    )
                    previousTransitArrival = last.connection.arrivalSec
                    index = cursor
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

    private fun boardBufferSeconds(previous: Previous?): Int = when (previous) {
        is Previous.Ride -> SAME_STOP_TRANSFER_BUFFER_SECONDS
        else -> 0
    }

    private fun transferNeighbors(stopIndex: Int): List<WalkLink> {
        transferCache[stopIndex]?.let { return it }
        val stop = stops[stopIndex]
        val links = nearbyStops(
            GeoPoint(stop.lat, stop.lon),
            TRANSFER_RADIUS_METERS,
            MAX_TRANSFER_NEIGHBORS + 1
        ).filter { it.stopIndex != stopIndex }.take(MAX_TRANSFER_NEIGHBORS)
        transferCache[stopIndex] = links
        return links
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
                        val conservativeMeters = (meters * WALK_DETOUR_FACTOR).toInt().coerceAtLeast(1)
                        candidates += WalkLink(index, conservativeMeters, walkingSeconds(conservativeMeters))
                    }
                }
            }
        }
        return candidates.sortedBy { it.seconds }.take(limit)
    }

    private fun walkingMeters(from: GeoPoint, to: GeoPoint): Int =
        (haversineMeters(from.lat, from.lon, to.lat, to.lon) * WALK_DETOUR_FACTOR)
            .toInt().coerceAtLeast(1)

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

    private sealed interface Previous {
        data class Access(val departureSec: Int, val arrivalSec: Int, val meters: Int) : Previous
        data class Ride(val fromIndex: Int, val connection: SurfaceConnection) : Previous
        data class Transfer(
            val fromIndex: Int,
            val departureSec: Int,
            val arrivalSec: Int,
            val meters: Int
        ) : Previous
    }

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
            val connection: SurfaceConnection
        ) : RawStep
    }

    private data class WalkLink(val stopIndex: Int, val meters: Int, val seconds: Int)

    companion object {
        private const val INF = Int.MAX_VALUE / 4
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val GRID_DEGREES = 0.005
        private const val APPROX_CELL_METERS = 550.0
        private const val WALK_DETOUR_FACTOR = 1.22
        private const val ACCESS_RADIUS_METERS = 1_500
        private const val EGRESS_RADIUS_METERS = 1_500
        private const val TRANSFER_RADIUS_METERS = 500
        private const val MAX_ACCESS_STOPS = 40
        private const val MAX_EGRESS_STOPS = 40
        private const val MAX_TRANSFER_NEIGHBORS = 12
        private const val SAME_STOP_TRANSFER_BUFFER_SECONDS = 45
        private const val MAX_SERVICE_SEC = 30 * 60 * 60
    }
}

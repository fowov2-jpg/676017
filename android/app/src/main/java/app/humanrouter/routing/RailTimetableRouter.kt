package app.humanrouter.routing

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Time-dependent router for the compact, audited MTPPK timetable asset.
 *
 * Only stations with coordinates verified against the runtime rail graph are present in the asset.
 * The timetable is intentionally separate from [RailGraphRouter]: MCD/TRAIN must never fall back to
 * a fabricated headway when a published departure is unavailable.
 */
internal class RailTimetableRouter private constructor(
    root: JSONObject,
    private val preferences: RoutePreferences,
    private val walkGraph: RuntimeWalkGraph?,
    private val zoneId: ZoneId
) {
    private data class Station(
        val id: Int,
        val name: String,
        val point: GeoPoint
    )

    private data class StopTime(
        val stationId: Int,
        val seconds: Int,
        val pickupAllowed: Boolean,
        val dropoffAllowed: Boolean
    )

    private data class Trip(
        val id: String,
        val mode: TransportMode,
        val number: String,
        val service: String,
        val stops: List<StopTime>
    )

    private data class WalkLink(
        val station: Station,
        val cost: RuntimeWalkGraph.WalkCost
    )

    val effectiveFrom: LocalDate? = root.optString("effective_from")
        .takeIf(String::isNotBlank)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val coverage: String = root.optString("coverage")
    val limitations: String = root.optString("limitations")

    private val stations: Map<Int, Station>
    private val trips: List<Trip>

    init {
        require(root.optInt("schema", -1) == 1) { "unsupported rail timetable schema" }

        val stationItems = root.getJSONArray("stations")
        val parsedStations = LinkedHashMap<Int, Station>(stationItems.length())
        for (index in 0 until stationItems.length()) {
            val item = stationItems.getJSONObject(index)
            val station = Station(
                id = item.getInt("id"),
                name = item.getString("name"),
                point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
            )
            require(station.id !in parsedStations) { "duplicate rail timetable station id" }
            parsedStations[station.id] = station
        }
        stations = parsedStations

        val tripItems = root.getJSONArray("trips")
        val parsedTrips = ArrayList<Trip>(tripItems.length())
        for (index in 0 until tripItems.length()) {
            val item = tripItems.getJSONObject(index)
            val mode = TransportMode.fromRuntimeValue(item.optString("mode"))
                ?.takeIf { it == TransportMode.MCD || it == TransportMode.TRAIN }
                ?: continue
            val stopItems = item.getJSONArray("stops")
            val stops = ArrayList<StopTime>(stopItems.length())
            var previous = -1
            for (stopIndex in 0 until stopItems.length()) {
                val values = stopItems.getJSONArray(stopIndex)
                val stationId = values.getInt(0)
                val seconds = values.getInt(1)
                if (stationId !in stations || seconds < previous) continue
                stops += StopTime(
                    stationId = stationId,
                    seconds = seconds,
                    pickupAllowed = values.optInt(2, 1) == 1,
                    dropoffAllowed = values.optInt(3, 1) == 1
                )
                previous = seconds
            }
            if (stops.size >= 2) {
                parsedTrips += Trip(
                    id = item.getString("id"),
                    mode = mode,
                    number = item.optString("number"),
                    service = item.optString("service", "published_default"),
                    stops = stops
                )
            }
        }
        trips = parsedTrips
        require(trips.isNotEmpty()) { "rail timetable has no routeable trips" }
    }

    fun findCandidates(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long,
        broadSearch: Boolean
    ): List<RouteCandidate> {
        val access = walkingLinks(origin, towardDestination = false).associateBy { it.station.id }
        val egress = walkingLinks(destination, towardDestination = true).associateBy { it.station.id }
        if (access.isEmpty() || egress.isEmpty()) return emptyList()

        val requestDate = Instant.ofEpochSecond(departureEpochSec).atZone(zoneId).toLocalDate()
        val found = ArrayList<RouteCandidate>()
        for (serviceDate in listOf(requestDate.minusDays(1), requestDate, requestDate.plusDays(1))) {
            if (effectiveFrom?.let(serviceDate::isBefore) == true) continue
            val midnight = serviceDate.atStartOfDay(zoneId).toEpochSecond()
            for (trip in trips) {
                if (!runsOn(trip.service, serviceDate)) continue
                for (boardIndex in 0 until trip.stops.lastIndex) {
                    val board = trip.stops[boardIndex]
                    if (!board.pickupAllowed) continue
                    val accessLink = access[board.stationId] ?: continue
                    val stationArrival = departureEpochSec + accessLink.cost.seconds
                    val tripDeparture = midnight + board.seconds
                    if (tripDeparture < stationArrival + ENTRY_SECONDS) continue

                    for (alightIndex in boardIndex + 1 until trip.stops.size) {
                        val alight = trip.stops[alightIndex]
                        if (!alight.dropoffAllowed) continue
                        val egressLink = egress[alight.stationId] ?: continue
                        val tripArrival = midnight + alight.seconds
                        if (tripArrival <= tripDeparture) continue
                        found += candidate(
                            trip = trip,
                            boardIndex = boardIndex,
                            alightIndex = alightIndex,
                            access = accessLink,
                            egress = egressLink,
                            requestedDeparture = departureEpochSec,
                            tripDeparture = tripDeparture,
                            tripArrival = tripArrival,
                            origin = origin,
                            destination = destination
                        )
                    }
                }
            }
        }

        val sorted = found.sortedWith(
            compareBy<RouteCandidate> { it.arrivalEpochSec }
                .thenBy { it.walkMeters }
                .thenBy { it.id }
        )
        val limit = if (broadSearch) BROAD_RESULT_LIMIT else FAST_RESULT_LIMIT
        val selected = LinkedHashMap<String, RouteCandidate>()
        for (mode in listOf(TransportMode.MCD, TransportMode.TRAIN)) {
            sorted.firstOrNull { candidate -> candidate.legs.any { it.mode == mode } }
                ?.let { selected[it.id] = it }
        }
        for (candidate in sorted) {
            if (selected.size >= limit) break
            selected.putIfAbsent(candidate.id, candidate)
        }
        return selected.values.sortedBy { it.arrivalEpochSec }.take(limit)
    }

    private fun candidate(
        trip: Trip,
        boardIndex: Int,
        alightIndex: Int,
        access: WalkLink,
        egress: WalkLink,
        requestedDeparture: Long,
        tripDeparture: Long,
        tripArrival: Long,
        origin: GeoPoint,
        destination: GeoPoint
    ): RouteCandidate {
        val board = access.station
        val alight = egress.station
        val accessArrival = requestedDeparture + access.cost.seconds
        val egressDeparture = tripArrival + EXIT_SECONDS
        val lineName = if (trip.mode == TransportMode.MCD) {
            listOf("D3", trip.number).filter(String::isNotBlank).joinToString(" · ")
        } else {
            trip.number
        }
        val legs = listOf(
            RouteLeg(
                mode = TransportMode.WALK,
                from = RoutePlace("origin", "Откуда", origin),
                to = board.place(),
                departureEpochSec = requestedDeparture,
                arrivalEpochSec = accessArrival,
                walkMeters = access.cost.meters,
                uncertaintySeconds = if (walkGraph != null) 30 else 90,
                realtimeConfidence = if (walkGraph != null) 0.95 else 0.72,
                geometry = access.cost.geometry
            ),
            RouteLeg(
                mode = trip.mode,
                from = board.place(),
                to = alight.place(),
                departureEpochSec = tripDeparture,
                arrivalEpochSec = tripArrival,
                lineId = trip.id,
                lineName = lineName,
                waitSeconds = (tripDeparture - accessArrival).toInt().coerceAtLeast(0),
                uncertaintySeconds = PUBLISHED_TIMETABLE_UNCERTAINTY_SECONDS,
                realtimeConfidence = PUBLISHED_TIMETABLE_CONFIDENCE,
                stopCount = alightIndex - boardIndex,
                geometry = trip.stops
                    .subList(boardIndex, alightIndex + 1)
                    .mapNotNull { stop -> stations[stop.stationId]?.point }
            ),
            RouteLeg(
                mode = TransportMode.WALK,
                from = alight.place(),
                to = RoutePlace("destination", "Куда", destination),
                departureEpochSec = egressDeparture,
                arrivalEpochSec = egressDeparture + egress.cost.seconds,
                walkMeters = egress.cost.meters,
                uncertaintySeconds = if (walkGraph != null) 30 else 90,
                realtimeConfidence = if (walkGraph != null) 0.95 else 0.72,
                geometry = egress.cost.geometry
            )
        )
        return RouteCandidate(
            id = "rail-tt-${trip.id.substringAfter(':')}-${board.id}-${alight.id}",
            requestedDepartureEpochSec = requestedDeparture,
            legs = legs
        )
    }

    private fun walkingLinks(point: GeoPoint, towardDestination: Boolean): List<WalkLink> {
        return stations.values.asSequence()
            .map { it to haversineMeters(point, it.point) }
            .filter { it.second <= MAX_ACCESS_GEOMETRIC_METERS }
            .sortedBy { it.second }
            .take(MAX_ACCESS_CANDIDATES)
            .mapNotNull { (station, geometricMeters) ->
                val cost = if (walkGraph != null) {
                    if (towardDestination) {
                        walkGraph.shortestWalk(station.point, point, ACCESS_MAX_SECONDS, ACCESS_MAX_METERS)
                    } else {
                        walkGraph.shortestWalk(point, station.point, ACCESS_MAX_SECONDS, ACCESS_MAX_METERS)
                    }
                } else RuntimeWalkGraph.WalkCost(
                    seconds = ceil(geometricMeters * WALK_DETOUR_FACTOR / preferences.walkingSpeedMetersPerSecond).toInt(),
                    meters = ceil(geometricMeters * WALK_DETOUR_FACTOR).toInt()
                )
                if (cost == null) return@mapNotNull null
                WalkLink(station, cost)
            }
            .sortedBy { it.cost.seconds }
            .take(MAX_EXACT_ACCESS)
            .toList()
    }

    private fun runsOn(service: String, date: LocalDate): Boolean = when (service) {
        "workdays" -> date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        "weekends" -> date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        else -> true
    }

    private fun Station.place(): RoutePlace = RoutePlace("rail-timetable:$id", name, point)

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val q = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(q)))
    }

    companion object {
        private const val ASSET_NAME = "rail_timetable_mtppk_2026-04-27.json"
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val WALK_DETOUR_FACTOR = 1.20
        private const val MAX_ACCESS_GEOMETRIC_METERS = 2_600
        private const val ACCESS_MAX_METERS = 3_200
        private const val ACCESS_MAX_SECONDS = 45 * 60
        private const val MAX_ACCESS_CANDIDATES = 7
        private const val MAX_EXACT_ACCESS = 5
        private const val ENTRY_SECONDS = 60L
        private const val EXIT_SECONDS = 45L
        private const val PUBLISHED_TIMETABLE_UNCERTAINTY_SECONDS = 180
        private const val PUBLISHED_TIMETABLE_CONFIDENCE = 0.82
        private const val FAST_RESULT_LIMIT = 3
        private const val BROAD_RESULT_LIMIT = 6

        fun openOrNull(
            context: Context,
            runtimeRoot: File,
            preferences: RoutePreferences,
            walkGraph: RuntimeWalkGraph?,
            zoneId: ZoneId = ZoneId.of("Europe/Moscow")
        ): RailTimetableRouter? {
            val runtimeFile = File(runtimeRoot, "rail/timetable.json")
            val text = runCatching {
                if (runtimeFile.exists()) runtimeFile.readText() else context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrNull() ?: return null
            return runCatching { RailTimetableRouter(JSONObject(text), preferences, walkGraph, zoneId) }.getOrNull()
        }

        internal fun fromJsonForTest(
            json: String,
            preferences: RoutePreferences = RoutePreferences(),
            zoneId: ZoneId = ZoneId.of("Europe/Moscow")
        ): RailTimetableRouter = RailTimetableRouter(JSONObject(json), preferences, null, zoneId)
    }
}

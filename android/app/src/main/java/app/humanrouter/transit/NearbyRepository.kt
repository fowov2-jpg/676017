package app.humanrouter.transit

import android.content.Context
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.SurfaceScheduleRepository
import app.humanrouter.routing.SurfaceStop
import app.humanrouter.routing.TransportMode
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class NearbyTransitPlace(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val distanceMeters: Int,
    val modes: Set<TransportMode>,
    val routeLabels: List<String>,
    val nextDepartureEpochSec: Long?
)

internal class NearbyRepository(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
) {
    private val cacheLock = Any()
    private var surfaceCache: SurfaceCache? = null
    private var railCache: RailCache? = null
    private var timetableCache: TimetableCache? = null

    fun findNearby(center: GeoPoint, nowEpochSec: Long, limit: Int = 6): List<NearbyTransitPlace> {
        val surface = loadSurface(center, nowEpochSec, limit * 2)
        val rail = loadRail(center, nowEpochSec, limit * 2)
        return (surface + rail)
            .sortedWith(compareBy<NearbyTransitPlace> { it.distanceMeters }.thenBy { it.name })
            .take(limit)
    }

    private fun loadSurface(center: GeoPoint, nowEpochSec: Long, limit: Int): List<NearbyTransitPlace> {
        return runCatching {
            SurfaceScheduleRepository(context).use { repository ->
                val nearest = surfaceIndex(repository).around(center, SURFACE_RADIUS_METERS).asSequence()
                    .map { stop -> stop to haversineMeters(center, GeoPoint(stop.lat, stop.lon)).toInt() }
                    .filter { it.second <= SURFACE_RADIUS_METERS }
                    .sortedBy { it.second }
                    .take(limit)
                    .toList()
                if (nearest.isEmpty()) return@use emptyList()

                val serviceDate = repository.serviceDate.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                val requestDate = Instant.ofEpochSecond(nowEpochSec).atZone(zoneId).toLocalDate()
                val serviceMidnight = serviceDate?.atStartOfDay(zoneId)?.toEpochSecond()
                val isCurrentServiceDay = serviceDate == requestDate && serviceMidnight != null
                val fromSec = if (isCurrentServiceDay) {
                    (nowEpochSec - serviceMidnight!!).toInt().coerceAtLeast(0)
                } else {
                    ROUTE_SAMPLE_START_SECONDS
                }
                val toSec = if (isCurrentServiceDay) {
                    fromSec + UPCOMING_WINDOW_SECONDS
                } else {
                    ROUTE_SAMPLE_END_SECONDS
                }
                val departures = repository.loadDepartures(
                    nearest.map { it.first.id },
                    fromSec,
                    toSec,
                    maxPerStop = MAX_ROUTES_PER_PLACE
                )

                nearest.mapNotNull { (stop, distance) ->
                    val atStop = departures[stop.id].orEmpty()
                    if (atStop.isEmpty()) return@mapNotNull null
                    NearbyTransitPlace(
                        id = "surface:${stop.id}",
                        name = stop.name,
                        point = GeoPoint(stop.lat, stop.lon),
                        distanceMeters = distance,
                        modes = atStop.map { it.route.mode }.toSet(),
                        routeLabels = atStop.mapNotNull { departure ->
                            departure.route.shortName?.takeIf(String::isNotBlank)
                                ?: departure.route.longName?.takeIf(String::isNotBlank)
                        }.distinct().take(MAX_ROUTES_PER_PLACE),
                        nextDepartureEpochSec = if (isCurrentServiceDay) {
                            atStop.minOfOrNull { serviceMidnight!! + it.departureSec }
                        } else {
                            null
                        }
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadRail(center: GeoPoint, nowEpochSec: Long, limit: Int): List<NearbyTransitPlace> {
        val graphPlaces = loadRailGraph(center, limit)
        val timetablePlaces = loadRailTimetable(center, nowEpochSec, limit)
        val merged = graphPlaces.toMutableList()
        for (timetable in timetablePlaces) {
            val existingIndex = merged.indexOfFirst { graph ->
                normalizeName(graph.name) == normalizeName(timetable.name) ||
                    haversineMeters(graph.point, timetable.point) <= RAIL_MERGE_RADIUS_METERS
            }
            if (existingIndex < 0) {
                merged += timetable
            } else {
                val existing = merged[existingIndex]
                merged[existingIndex] = existing.copy(
                    modes = existing.modes + timetable.modes,
                    routeLabels = (existing.routeLabels + timetable.routeLabels)
                        .distinct()
                        .take(MAX_ROUTES_PER_PLACE),
                    nextDepartureEpochSec = listOfNotNull(
                        existing.nextDepartureEpochSec,
                        timetable.nextDepartureEpochSec
                    ).minOrNull()
                )
            }
        }
        return merged.sortedBy { it.distanceMeters }.take(limit)
    }

    private fun loadRailGraph(center: GeoPoint, limit: Int): List<NearbyTransitPlace> {
        val graph = File(context.filesDir, "runtime/rail/graph.json")
        if (!graph.exists()) return emptyList()
        return runCatching {
            railIndex(graph).around(center, RAIL_RADIUS_METERS).asSequence()
                .map { station -> station to haversineMeters(center, station.point).toInt() }
                .filter { it.second <= RAIL_RADIUS_METERS }
                .sortedBy { it.second }
                .take(limit)
                .map { (station, distance) ->
                    NearbyTransitPlace(
                        id = "rail:${station.id}",
                        name = station.name,
                        point = station.point,
                        distanceMeters = distance,
                        modes = station.modes,
                        routeLabels = station.labels.take(MAX_ROUTES_PER_PLACE),
                        nextDepartureEpochSec = null
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun loadRailTimetable(
        center: GeoPoint,
        nowEpochSec: Long,
        limit: Int
    ): List<NearbyTransitPlace> = runCatching {
        val timetable = timetableIndex()
        timetable.index.around(center, RAIL_RADIUS_METERS).asSequence()
            .map { station -> station to haversineMeters(center, station.point).toInt() }
            .filter { it.second <= RAIL_RADIUS_METERS }
            .sortedBy { it.second }
            .take(limit)
            .map { (station, distance) ->
                NearbyTransitPlace(
                    id = "rail-timetable:${station.id}",
                    name = station.name,
                    point = station.point,
                    distanceMeters = distance,
                    modes = station.departures.mapTo(LinkedHashSet()) { it.mode },
                    routeLabels = station.departures.map(TimetableDeparture::label)
                        .filter(String::isNotBlank)
                        .distinct()
                        .take(MAX_ROUTES_PER_PLACE),
                    nextDepartureEpochSec = nextTimetableDeparture(
                        station,
                        nowEpochSec,
                        timetable.effectiveFrom
                    )
                )
            }
            .toList()
    }.getOrDefault(emptyList())

    private fun surfaceIndex(repository: SurfaceScheduleRepository): SpatialBuckets<SurfaceStop> {
        val token = repository.cacheToken
        synchronized(cacheLock) {
            surfaceCache?.takeIf { it.token == token }?.let { return it.index }
            return SpatialBuckets(repository.loadStops()) { GeoPoint(it.lat, it.lon) }
                .also { surfaceCache = SurfaceCache(token, it) }
        }
    }

    private fun railIndex(graph: File): SpatialBuckets<RailStation> {
        val token = "${graph.length()}:${graph.lastModified()}"
        synchronized(cacheLock) {
            railCache?.takeIf { it.token == token }?.let { return it.index }

            val stations = LinkedHashMap<String, RailStation>()
            val routes = JSONObject(graph.readText()).getJSONArray("routes")
            for (routeIndex in 0 until routes.length()) {
                val route = routes.getJSONObject(routeIndex)
                if (!route.optBoolean("routeable", false)) continue
                val mode = TransportMode.fromRuntimeValue(route.optString("mode"))
                    ?.takeIf { it in RAIL_MODES }
                    ?: continue
                val label = route.optString("ref").takeIf(String::isNotBlank)
                    ?: route.optString("name").takeIf(String::isNotBlank)
                    ?: mode.name
                val stops = route.getJSONArray("stops")
                for (i in 0 until stops.length()) {
                    val stop = stops.getJSONObject(i)
                    val id = stop.getString("osm_stop_id")
                    val station = stations.getOrPut(id) {
                        RailStation(
                            id = id,
                            name = stop.optString("name", id),
                            point = GeoPoint(stop.getDouble("lat"), stop.getDouble("lon"))
                        )
                    }
                    station.modes += mode
                    station.labels += label
                }
            }
            return SpatialBuckets(stations.values.toList(), RailStation::point)
                .also { railCache = RailCache(token, it) }
        }
    }

    private fun timetableIndex(): TimetableCache {
        val runtimeFile = File(context.filesDir, "runtime/rail/timetable.json")
        val token = if (runtimeFile.exists()) {
            "runtime:${runtimeFile.length()}:${runtimeFile.lastModified()}"
        } else {
            "asset:$RAIL_TIMETABLE_ASSET"
        }
        synchronized(cacheLock) {
            timetableCache?.takeIf { it.token == token }?.let { return it }
            val text = if (runtimeFile.exists()) {
                runtimeFile.readText()
            } else {
                context.assets.open(RAIL_TIMETABLE_ASSET).bufferedReader().use { it.readText() }
            }
            val root = JSONObject(text)
            require(root.optInt("schema", -1) == 1) { "unsupported rail timetable schema" }
            val stations = LinkedHashMap<Int, TimetableStation>()
            val stationItems = root.getJSONArray("stations")
            for (index in 0 until stationItems.length()) {
                val item = stationItems.getJSONObject(index)
                val station = TimetableStation(
                    id = item.getInt("id"),
                    name = item.getString("name"),
                    point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                )
                stations[station.id] = station
            }
            val trips = root.getJSONArray("trips")
            for (tripIndex in 0 until trips.length()) {
                val trip = trips.getJSONObject(tripIndex)
                val mode = TransportMode.fromRuntimeValue(trip.optString("mode"))
                    ?.takeIf { it == TransportMode.MCD || it == TransportMode.TRAIN }
                    ?: continue
                val number = trip.optString("number")
                val label = if (mode == TransportMode.MCD) "D3" else number
                val service = trip.optString("service", "published_default")
                val stops = trip.getJSONArray("stops")
                for (stopIndex in 0 until stops.length()) {
                    val values = stops.getJSONArray(stopIndex)
                    if (values.optInt(2, 1) != 1) continue
                    stations[values.getInt(0)]?.departures?.add(
                        TimetableDeparture(
                            mode = mode,
                            label = label,
                            service = service,
                            seconds = values.getInt(1)
                        )
                    )
                }
            }
            val effectiveFrom = root.optString("effective_from")
                .takeIf(String::isNotBlank)
                ?.let(LocalDate::parse)
            return TimetableCache(
                token = token,
                effectiveFrom = effectiveFrom,
                index = SpatialBuckets(stations.values.filter { it.departures.isNotEmpty() }, TimetableStation::point)
            ).also { timetableCache = it }
        }
    }

    private fun nextTimetableDeparture(
        station: TimetableStation,
        nowEpochSec: Long,
        effectiveFrom: LocalDate?
    ): Long? {
        val requestDate = Instant.ofEpochSecond(nowEpochSec).atZone(zoneId).toLocalDate()
        var best: Long? = null
        for (serviceDate in listOf(requestDate.minusDays(1), requestDate, requestDate.plusDays(1))) {
            if (effectiveFrom?.let(serviceDate::isBefore) == true) continue
            val midnight = serviceDate.atStartOfDay(zoneId).toEpochSecond()
            for (departure in station.departures) {
                if (!runsOn(departure.service, serviceDate)) continue
                val epoch = midnight + departure.seconds
                if (epoch < nowEpochSec || epoch > nowEpochSec + UPCOMING_RAIL_WINDOW_SECONDS) continue
                if (best == null || epoch < best) best = epoch
            }
        }
        return best
    }

    private fun runsOn(service: String, date: LocalDate): Boolean = when (service) {
        "workdays" -> date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        "weekends" -> date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        else -> true
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
        private const val SURFACE_RADIUS_METERS = 2_000
        private const val RAIL_RADIUS_METERS = 3_500
        private const val UPCOMING_WINDOW_SECONDS = 2 * 60 * 60
        private const val UPCOMING_RAIL_WINDOW_SECONDS = 6 * 60 * 60L
        private const val ROUTE_SAMPLE_START_SECONDS = 10 * 60 * 60
        private const val ROUTE_SAMPLE_END_SECONDS = 14 * 60 * 60
        private const val MAX_ROUTES_PER_PLACE = 8
        private const val GRID_CELL_DEGREES = 0.02
        private const val RAIL_MERGE_RADIUS_METERS = 180
        private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
        private const val RAIL_TIMETABLE_ASSET = "rail_timetable_mtppk_2026-04-27.json"
        private val RAIL_MODES = setOf(
            TransportMode.METRO,
            TransportMode.MCC,
            TransportMode.MCD,
            TransportMode.TRAIN
        )
    }

    private data class SurfaceCache(
        val token: String,
        val index: SpatialBuckets<SurfaceStop>
    )

    private data class RailCache(
        val token: String,
        val index: SpatialBuckets<RailStation>
    )

    private data class TimetableCache(
        val token: String,
        val effectiveFrom: LocalDate?,
        val index: SpatialBuckets<TimetableStation>
    )

    private data class RailStation(
        val id: String,
        val name: String,
        val point: GeoPoint,
        val modes: MutableSet<TransportMode> = LinkedHashSet(),
        val labels: MutableSet<String> = LinkedHashSet()
    )

    private data class TimetableStation(
        val id: Int,
        val name: String,
        val point: GeoPoint,
        val departures: MutableList<TimetableDeparture> = ArrayList()
    )

    private data class TimetableDeparture(
        val mode: TransportMode,
        val label: String,
        val service: String,
        val seconds: Int
    )

    /** In-memory spatial buckets are rebuilt only when the installed runtime files change. */
    private class SpatialBuckets<T>(
        entries: List<T>,
        private val pointOf: (T) -> GeoPoint
    ) {
        private val buckets = entries.groupBy { entry -> cellKey(pointOf(entry)) }

        fun around(center: GeoPoint, radiusMeters: Int): List<T> {
            val latitudeDegrees = radiusMeters / METERS_PER_LATITUDE_DEGREE
            val longitudeScale = max(0.1, cos(center.lat * PI / 180.0))
            val longitudeDegrees = radiusMeters / (METERS_PER_LATITUDE_DEGREE * longitudeScale)
            val minLatCell = cellIndex(center.lat - latitudeDegrees)
            val maxLatCell = cellIndex(center.lat + latitudeDegrees)
            val minLonCell = cellIndex(center.lon - longitudeDegrees)
            val maxLonCell = cellIndex(center.lon + longitudeDegrees)
            return buildList {
                for (latCell in minLatCell..maxLatCell) {
                    for (lonCell in minLonCell..maxLonCell) {
                        addAll(buckets[cellKey(latCell, lonCell)].orEmpty())
                    }
                }
            }
        }

        private fun cellKey(point: GeoPoint): Long = cellKey(cellIndex(point.lat), cellIndex(point.lon))

        private fun cellIndex(value: Double): Int = floor(value / GRID_CELL_DEGREES).toInt()

        private fun cellKey(latCell: Int, lonCell: Int): Long =
            (latCell.toLong() shl 32) xor (lonCell.toLong() and 0xffffffffL)
    }
}

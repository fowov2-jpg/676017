package app.humanrouter.transit

import android.content.Context
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.SurfaceScheduleRepository
import app.humanrouter.routing.TransportMode
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Map-only transport-place index.
 *
 * The home "Рядом" card intentionally stays short. The map needs a denser set of tappable symbols,
 * so this repository builds a separate immutable index from the installed surface/rail runtime and
 * returns only places around the camera center. No timetable scan or network request occurs while
 * panning the map.
 */
internal class TransitMapMarkerRepository(private val context: Context) {
    private val lock = Any()
    private var cache: Cache? = null

    fun around(
        center: GeoPoint,
        radiusMeters: Int,
        limit: Int = 140
    ): List<NearbyTransitPlace> {
        val radius = radiusMeters.coerceIn(800, 15_000)
        val boundedLimit = limit.coerceIn(12, 220)
        val index = loadIndex()
        return index.around(center, radius).asSequence()
            .map { place -> place to haversineMeters(center, place.point).toInt() }
            .filter { (_, distance) -> distance <= radius }
            .sortedWith(
                compareBy<Pair<MapPlace, Int>> { it.second }
                    .thenBy { markerPriority(it.first.modes) }
                    .thenBy { it.first.name }
            )
            .take(boundedLimit)
            .map { (place, distance) ->
                NearbyTransitPlace(
                    id = place.id,
                    name = place.name,
                    point = place.point,
                    distanceMeters = distance,
                    modes = place.modes,
                    routeLabels = place.labels,
                    nextDepartureEpochSec = null
                )
            }
            .toList()
    }

    private fun loadIndex(): SpatialIndex {
        val token = dataToken()
        synchronized(lock) {
            cache?.takeIf { it.token == token }?.let { return it.index }
        }
        val places = ArrayList<MapPlace>(24_000)
        loadSurface(places)
        loadRailGraph(places)
        loadRailTimetable(places)
        val index = SpatialIndex(deduplicateRail(places))
        synchronized(lock) { cache = Cache(token, index) }
        return index
    }

    private fun loadSurface(output: MutableList<MapPlace>) {
        runCatching {
            SurfaceScheduleRepository(context).use { repository ->
                repository.loadStops().forEach { stop ->
                    output += MapPlace(
                        id = "surface:${stop.id}",
                        name = stop.name,
                        point = GeoPoint(stop.lat, stop.lon),
                        modes = setOf(
                            if (stop.transportType?.contains("tram", ignoreCase = true) == true) {
                                TransportMode.TRAM
                            } else {
                                TransportMode.BUS
                            }
                        ),
                        labels = emptyList(),
                        source = Source.SURFACE
                    )
                }
            }
        }
    }

    private fun loadRailGraph(output: MutableList<MapPlace>) {
        val graph = File(context.filesDir, "runtime/rail/graph.json")
        if (!graph.exists()) return
        runCatching {
            val merged = LinkedHashMap<String, MutableRailPlace>()
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
                for (index in 0 until stops.length()) {
                    val stop = stops.getJSONObject(index)
                    val id = stop.optString("osm_stop_id").takeIf(String::isNotBlank)
                        ?: continue
                    val entry = merged.getOrPut(id) {
                        MutableRailPlace(
                            id = id,
                            name = stop.optString("name", id),
                            point = GeoPoint(stop.getDouble("lat"), stop.getDouble("lon"))
                        )
                    }
                    entry.modes += mode
                    entry.labels += label
                }
            }
            merged.values.forEach { station ->
                output += MapPlace(
                    id = "rail:${station.id}",
                    name = station.name,
                    point = station.point,
                    modes = station.modes,
                    labels = station.labels.take(8),
                    source = Source.RAIL_GRAPH
                )
            }
        }
    }

    private fun loadRailTimetable(output: MutableList<MapPlace>) {
        val runtime = File(context.filesDir, "runtime/rail/timetable.json")
        val text = when {
            runtime.exists() -> runCatching { runtime.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(RAIL_TIMETABLE_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return

        runCatching {
            val root = JSONObject(text)
            val stationsJson = root.getJSONArray("stations")
            val stations = LinkedHashMap<Int, TimetablePlace>()
            for (index in 0 until stationsJson.length()) {
                val item = stationsJson.getJSONObject(index)
                val id = item.getInt("id")
                stations[id] = TimetablePlace(
                    id = id,
                    name = item.getString("name"),
                    point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                )
            }
            val trips = root.getJSONArray("trips")
            for (tripIndex in 0 until trips.length()) {
                val trip = trips.getJSONObject(tripIndex)
                val mode = TransportMode.fromRuntimeValue(trip.optString("mode"))
                    ?.takeIf { it == TransportMode.MCD || it == TransportMode.TRAIN }
                    ?: continue
                val label = if (mode == TransportMode.MCD) {
                    trip.optString("line").takeIf(String::isNotBlank) ?: "D3"
                } else {
                    trip.optString("number").takeIf(String::isNotBlank) ?: "Поезд"
                }
                val stops = trip.getJSONArray("stops")
                for (stopIndex in 0 until stops.length()) {
                    val values = stops.getJSONArray(stopIndex)
                    val station = stations[values.getInt(0)] ?: continue
                    station.modes += mode
                    station.labels += label
                }
            }
            stations.values.filter { it.modes.isNotEmpty() }.forEach { station ->
                output += MapPlace(
                    id = "rail-timetable:${station.id}",
                    name = station.name,
                    point = station.point,
                    modes = station.modes,
                    labels = station.labels.take(8),
                    source = Source.RAIL_TIMETABLE
                )
            }
        }
    }

    /** Keep opposite surface platforms separate, but collapse duplicate graph/timetable rail stations. */
    private fun deduplicateRail(input: List<MapPlace>): List<MapPlace> {
        val output = ArrayList<MapPlace>(input.size)
        for (place in input) {
            if (place.source == Source.SURFACE) {
                output += place
                continue
            }
            val existingIndex = output.indexOfFirst { current ->
                current.source != Source.SURFACE &&
                    normalize(current.name) == normalize(place.name) &&
                    haversineMeters(current.point, place.point) <= RAIL_MERGE_METERS
            }
            if (existingIndex < 0) {
                output += place
            } else {
                val existing = output[existingIndex]
                output[existingIndex] = existing.copy(
                    modes = existing.modes + place.modes,
                    labels = (existing.labels + place.labels).distinct().take(8)
                )
            }
        }
        return output
    }

    private fun dataToken(): String {
        val runtime = File(context.filesDir, "runtime")
        return listOf(
            File(runtime, "surface/manifest.json"),
            File(runtime, "rail/graph.json"),
            File(runtime, "rail/timetable.json")
        ).joinToString("|") { file ->
            "${file.path}:${file.length()}:${file.lastModified()}"
        }
    }

    private fun markerPriority(modes: Set<TransportMode>): Int = when {
        TransportMode.METRO in modes -> 0
        TransportMode.MCC in modes -> 1
        TransportMode.MCD in modes -> 2
        TransportMode.TRAM in modes -> 3
        TransportMode.TRAIN in modes -> 4
        else -> 5
    }

    private fun normalize(value: String): String = value
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

    private data class Cache(val token: String, val index: SpatialIndex)

    private data class MapPlace(
        val id: String,
        val name: String,
        val point: GeoPoint,
        val modes: Set<TransportMode>,
        val labels: List<String>,
        val source: Source
    )

    private enum class Source { SURFACE, RAIL_GRAPH, RAIL_TIMETABLE }

    private data class MutableRailPlace(
        val id: String,
        val name: String,
        val point: GeoPoint,
        val modes: MutableSet<TransportMode> = LinkedHashSet(),
        val labels: MutableSet<String> = LinkedHashSet()
    )

    private data class TimetablePlace(
        val id: Int,
        val name: String,
        val point: GeoPoint,
        val modes: MutableSet<TransportMode> = LinkedHashSet(),
        val labels: MutableSet<String> = LinkedHashSet()
    )

    private class SpatialIndex(entries: List<MapPlace>) {
        private val buckets = entries.groupBy { place -> cellKey(place.point) }

        fun around(center: GeoPoint, radiusMeters: Int): List<MapPlace> {
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

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
        private const val GRID_CELL_DEGREES = 0.02
        private const val RAIL_MERGE_METERS = 150.0
        private const val RAIL_TIMETABLE_ASSET = "rail_timetable_mtppk_2026-04-27.json"
        private val RAIL_MODES = setOf(
            TransportMode.METRO,
            TransportMode.MCC,
            TransportMode.MCD,
            TransportMode.TRAIN
        )
    }
}

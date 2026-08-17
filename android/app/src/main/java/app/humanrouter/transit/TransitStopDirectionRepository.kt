package app.humanrouter.transit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.TransportMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A real direction served from installed runtime data, never inferred from a route number alone. */
internal data class TransitDirectionOption(
    val mode: TransportMode,
    val routeLabel: String,
    val headsign: String,
    val radialHint: String?,
    val nextDepartureEpochSec: Long?
)

/**
 * Resolves directions for a clicked transport platform from the same offline runtime used by routing.
 *
 * Surface directions use the concrete trip_id, its next stop and terminal. Rail graph directions use
 * adjacent/terminal stations. MCD/train directions use the published timetable trip. `radialHint` is
 * deliberately omitted for tangential/ring movement instead of inventing "to centre" semantics.
 */
internal class TransitStopDirectionRepository(
    context: Context,
    private val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
) {
    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.filesDir, "runtime")

    fun directionsFor(
        place: NearbyTransitPlace,
        nowEpochSec: Long = Instant.now().epochSecond,
        limit: Int = 8
    ): List<TransitDirectionOption> {
        val boundedLimit = limit.coerceIn(1, 16)
        val result = ArrayList<TransitDirectionOption>(boundedLimit * 2)
        if (place.modes.any { it == TransportMode.BUS || it == TransportMode.TRAM }) {
            result += surfaceDirections(place, nowEpochSec, boundedLimit)
        }
        if (place.modes.any { it == TransportMode.METRO || it == TransportMode.MCC }) {
            result += railGraphDirections(place, boundedLimit)
        }
        if (place.modes.any { it == TransportMode.MCD || it == TransportMode.TRAIN }) {
            result += railTimetableDirections(place, nowEpochSec, boundedLimit)
        }
        return result
            .distinctBy { "${it.mode}|${it.routeLabel}|${normalize(it.headsign)}" }
            .sortedWith(
                compareBy<TransitDirectionOption> { it.nextDepartureEpochSec ?: Long.MAX_VALUE }
                    .thenBy { it.mode.ordinal }
                    .thenBy { it.routeLabel }
            )
            .take(boundedLimit)
    }

    private fun surfaceDirections(
        place: NearbyTransitPlace,
        nowEpochSec: Long,
        limit: Int
    ): List<TransitDirectionOption> {
        val stopId = place.id.removePrefix("surface:").takeIf { place.id.startsWith("surface:") }
            ?.toIntOrNull() ?: return emptyList()
        val surfaceRoot = File(runtimeRoot, "surface")
        val manifestFile = File(surfaceRoot, "manifest.json")
        if (!manifestFile.exists()) return emptyList()

        return runCatching {
            val manifest = JSONObject(manifestFile.readText())
            val databaseFile = File(surfaceRoot, manifest.getString("primary_file"))
            if (!databaseFile.exists()) return@runCatching emptyList()
            val serviceDate = manifest.optString("service_date").takeIf(String::isNotBlank)?.let(LocalDate::parse)
            val requestDate = Instant.ofEpochSecond(nowEpochSec).atZone(zoneId).toLocalDate()
            val currentServiceDay = serviceDate == requestDate
            val serviceMidnight = requestDate.atStartOfDay(zoneId).toEpochSecond()
            val fromSec = if (currentServiceDay) {
                (nowEpochSec - serviceMidnight).toInt().coerceAtLeast(0)
            } else {
                SAMPLE_START_SECONDS
            }
            val toSec = if (currentServiceDay) fromSec + SURFACE_WINDOW_SECONDS else SAMPLE_END_SECONDS

            val db = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val found = LinkedHashMap<String, TransitDirectionOption>()
                db.rawQuery(
                    """
                    SELECT c.dep,c.trip_id,r.route_id,r.short_name,r.long_name,r.route_mode,
                           (SELECT s_end.name
                              FROM connections c_end
                              JOIN stops s_end ON s_end.stop_id=c_end.to_stop
                             WHERE c_end.trip_id=c.trip_id
                             ORDER BY c_end.seq DESC
                             LIMIT 1) AS headsign,
                           s_next.lat,s_next.lon
                      FROM connections c
                      JOIN routes r ON r.route_id=c.route_id
                      JOIN stops s_next ON s_next.stop_id=c.to_stop
                     WHERE c.from_stop=? AND c.dep>=? AND c.dep<=?
                     ORDER BY c.dep
                     LIMIT 96
                    """.trimIndent(),
                    arrayOf(stopId.toString(), fromSec.toString(), toSec.toString())
                ).use { cursor ->
                    while (cursor.moveToNext() && found.size < limit * 3) {
                        val rawMode = cursor.getString(5)
                        val mode = TransportMode.fromRuntimeValue(rawMode)
                            ?.takeIf { it == TransportMode.BUS || it == TransportMode.TRAM }
                            ?: continue
                        val routeLabel = listOfNotNull(
                            cursor.getStringOrNull(3),
                            cursor.getStringOrNull(4),
                            cursor.getStringOrNull(2)
                        ).firstOrNull { it.isNotBlank() } ?: continue
                        val headsign = cursor.getStringOrNull(6)?.takeIf(String::isNotBlank) ?: continue
                        val next = GeoPoint(cursor.getDouble(7), cursor.getDouble(8))
                        val departure = if (currentServiceDay) serviceMidnight + cursor.getInt(0) else null
                        val option = TransitDirectionOption(
                            mode = mode,
                            routeLabel = routeLabel,
                            headsign = headsign,
                            radialHint = radialHint(place.point, next),
                            nextDepartureEpochSec = departure
                        )
                        val key = "${mode.name}|${normalize(routeLabel)}|${normalize(headsign)}"
                        val existing = found[key]
                        if (existing == null || earlier(option.nextDepartureEpochSec, existing.nextDepartureEpochSec)) {
                            found[key] = option
                        }
                    }
                }
                found.values.take(limit)
            } finally {
                db.close()
            }
        }.getOrDefault(emptyList())
    }

    private fun railGraphDirections(place: NearbyTransitPlace, limit: Int): List<TransitDirectionOption> {
        val graphFile = File(runtimeRoot, "rail/graph.json")
        if (!graphFile.exists()) return emptyList()
        val targetId = place.id.removePrefix("rail:").takeIf { place.id.startsWith("rail:") }

        return runCatching {
            val result = LinkedHashMap<String, TransitDirectionOption>()
            val routes = JSONObject(graphFile.readText()).getJSONArray("routes")
            for (routeIndex in 0 until routes.length()) {
                if (result.size >= limit * 3) break
                val route = routes.getJSONObject(routeIndex)
                if (!route.optBoolean("routeable", false)) continue
                val mode = TransportMode.fromRuntimeValue(route.optString("mode"))
                    ?.takeIf { it == TransportMode.METRO || it == TransportMode.MCC }
                    ?: continue
                val routeLabel = route.optString("ref").takeIf(String::isNotBlank)
                    ?: route.optString("name").takeIf(String::isNotBlank)
                    ?: modeLabel(mode)
                val stops = route.getJSONArray("stops")
                if (stops.length() < 2) continue
                for (index in 0 until stops.length()) {
                    val stop = stops.getJSONObject(index)
                    val matches = if (targetId != null) {
                        stop.optString("osm_stop_id") == targetId
                    } else {
                        normalize(stop.optString("name")) == normalize(place.name)
                    }
                    if (!matches) continue

                    if (index > 0) {
                        val previous = stops.getJSONObject(index - 1)
                        val terminal = stops.getJSONObject(0)
                        addRailOption(result, mode, routeLabel, terminal, previous, place.point)
                    }
                    if (index < stops.length() - 1) {
                        val next = stops.getJSONObject(index + 1)
                        val terminal = stops.getJSONObject(stops.length() - 1)
                        addRailOption(result, mode, routeLabel, terminal, next, place.point)
                    }
                }
            }
            result.values.take(limit)
        }.getOrDefault(emptyList())
    }

    private fun addRailOption(
        output: MutableMap<String, TransitDirectionOption>,
        mode: TransportMode,
        routeLabel: String,
        terminal: JSONObject,
        next: JSONObject,
        current: GeoPoint
    ) {
        val headsign = terminal.optString("name").takeIf(String::isNotBlank) ?: return
        val nextPoint = next.pointOrNull() ?: return
        val option = TransitDirectionOption(
            mode = mode,
            routeLabel = routeLabel,
            headsign = headsign,
            radialHint = radialHint(current, nextPoint),
            nextDepartureEpochSec = null
        )
        output.putIfAbsent("${mode.name}|${normalize(routeLabel)}|${normalize(headsign)}", option)
    }

    private fun railTimetableDirections(
        place: NearbyTransitPlace,
        nowEpochSec: Long,
        limit: Int
    ): List<TransitDirectionOption> {
        val runtimeFile = File(runtimeRoot, "rail/timetable.json")
        val text = when {
            runtimeFile.exists() -> runCatching { runtimeFile.readText() }.getOrNull()
            else -> runCatching {
                appContext.assets.open(RAIL_TIMETABLE_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return emptyList()

        return runCatching {
            val root = JSONObject(text)
            val stationsJson = root.getJSONArray("stations")
            val stations = LinkedHashMap<Int, TimetableStationLite>()
            for (index in 0 until stationsJson.length()) {
                val item = stationsJson.getJSONObject(index)
                stations[item.getInt("id")] = TimetableStationLite(
                    id = item.getInt("id"),
                    name = item.getString("name"),
                    point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                )
            }
            val explicitId = place.id.removePrefix("rail-timetable:")
                .takeIf { place.id.startsWith("rail-timetable:") }
                ?.toIntOrNull()
            val station = explicitId?.let(stations::get) ?: stations.values
                .asSequence()
                .filter { normalize(it.name) == normalize(place.name) || haversineMeters(it.point, place.point) <= 180.0 }
                .minByOrNull { haversineMeters(it.point, place.point) }
                ?: return@runCatching emptyList()

            val effectiveFrom = root.optString("effective_from").takeIf(String::isNotBlank)?.let(LocalDate::parse)
            val requestDate = Instant.ofEpochSecond(nowEpochSec).atZone(zoneId).toLocalDate()
            val result = LinkedHashMap<String, TransitDirectionOption>()
            val trips = root.getJSONArray("trips")
            for (tripIndex in 0 until trips.length()) {
                val trip = trips.getJSONObject(tripIndex)
                val mode = TransportMode.fromRuntimeValue(trip.optString("mode"))
                    ?.takeIf { it == TransportMode.MCD || it == TransportMode.TRAIN }
                    ?: continue
                val stops = trip.getJSONArray("stops")
                val index = findStationIndex(stops, station.id)
                if (index < 0 || index >= stops.length() - 1) continue
                val values = stops.getJSONArray(index)
                if (values.optInt(2, 1) != 1) continue
                val terminalId = stops.getJSONArray(stops.length() - 1).getInt(0)
                val nextId = stops.getJSONArray(index + 1).getInt(0)
                val terminal = stations[terminalId] ?: continue
                val next = stations[nextId] ?: continue
                val number = trip.optString("number")
                val routeLabel = if (mode == TransportMode.MCD) "D3" else number.takeIf(String::isNotBlank) ?: "Поезд"
                val service = trip.optString("service", "published_default")
                val departureEpoch = departureEpoch(
                    requestDate = requestDate,
                    departureSeconds = values.getInt(1),
                    service = service,
                    effectiveFrom = effectiveFrom,
                    nowEpochSec = nowEpochSec
                )
                if (departureEpoch != null && departureEpoch > nowEpochSec + RAIL_WINDOW_SECONDS) continue
                val option = TransitDirectionOption(
                    mode = mode,
                    routeLabel = routeLabel,
                    headsign = terminal.name,
                    radialHint = radialHint(place.point, next.point),
                    nextDepartureEpochSec = departureEpoch
                )
                val key = "${mode.name}|${normalize(routeLabel)}|${normalize(terminal.name)}"
                val existing = result[key]
                if (existing == null || earlier(option.nextDepartureEpochSec, existing.nextDepartureEpochSec)) {
                    result[key] = option
                }
            }
            result.values.take(limit)
        }.getOrDefault(emptyList())
    }

    private fun departureEpoch(
        requestDate: LocalDate,
        departureSeconds: Int,
        service: String,
        effectiveFrom: LocalDate?,
        nowEpochSec: Long
    ): Long? {
        var best: Long? = null
        for (date in listOf(requestDate.minusDays(1), requestDate, requestDate.plusDays(1))) {
            if (effectiveFrom?.let(date::isBefore) == true || !runsOn(service, date)) continue
            val epoch = date.atStartOfDay(zoneId).toEpochSecond() + departureSeconds
            if (epoch < nowEpochSec) continue
            if (best == null || epoch < best) best = epoch
        }
        return best
    }

    private fun runsOn(service: String, date: LocalDate): Boolean = when (service) {
        "workdays" -> date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        "weekends" -> date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        else -> true
    }

    private fun findStationIndex(stops: JSONArray, stationId: Int): Int {
        for (index in 0 until stops.length()) {
            if (stops.getJSONArray(index).getInt(0) == stationId) return index
        }
        return -1
    }

    private fun radialHint(current: GeoPoint, next: GeoPoint): String? {
        val currentDistance = haversineMeters(current, MOSCOW_CENTER)
        val nextDistance = haversineMeters(next, MOSCOW_CENTER)
        return when {
            nextDistance + RADIAL_DECISION_METERS < currentDistance -> "в центр"
            nextDistance > currentDistance + RADIAL_DECISION_METERS -> "из центра"
            else -> null
        }
    }

    private fun earlier(candidate: Long?, existing: Long?): Boolean = when {
        candidate == null -> false
        existing == null -> true
        else -> candidate < existing
    }

    private fun modeLabel(mode: TransportMode): String = when (mode) {
        TransportMode.METRO -> "Метро"
        TransportMode.MCC -> "МЦК"
        TransportMode.MCD -> "МЦД"
        TransportMode.TRAIN -> "Поезд"
        TransportMode.TRAM -> "Трамвай"
        TransportMode.BUS -> "Автобус"
        TransportMode.WALK -> "Пешком"
    }

    private fun JSONObject.pointOrNull(): GeoPoint? {
        val lat = optDouble("lat", Double.NaN)
        val lon = optDouble("lon", Double.NaN)
        return if (lat.isFinite() && lon.isFinite()) GeoPoint(lat, lon) else null
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

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

    private data class TimetableStationLite(
        val id: Int,
        val name: String,
        val point: GeoPoint
    )

    companion object {
        private val MOSCOW_CENTER = GeoPoint(55.7520, 37.6175)
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val RADIAL_DECISION_METERS = 90.0
        private const val SURFACE_WINDOW_SECONDS = 2 * 60 * 60
        private const val RAIL_WINDOW_SECONDS = 6 * 60 * 60L
        private const val SAMPLE_START_SECONDS = 10 * 60 * 60
        private const val SAMPLE_END_SECONDS = 14 * 60 * 60
        private const val RAIL_TIMETABLE_ASSET = "rail_timetable_mtppk_2026-04-27.json"
    }
}

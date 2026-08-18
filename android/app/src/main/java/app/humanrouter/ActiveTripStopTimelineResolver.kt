package app.humanrouter

import android.content.Context
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.SurfaceConnection
import app.humanrouter.routing.SurfaceScheduleRepository
import app.humanrouter.routing.TransportMode
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** One trusted passenger-visible stop/station time inside a single transit leg. */
internal data class ActiveTripResolvedStop(
    val name: String,
    val arrivalEpochSec: Long,
    val departureEpochSec: Long = arrivalEpochSec
)

/**
 * Reconstructs the stop-level timeline from the same runtime sources that produced the route.
 *
 * This deliberately returns an empty list when the source cannot be matched unambiguously. The UI
 * then falls back to the already validated aggregate leg timeline rather than inventing stop names
 * or times. All disk/database work is expected to run off the main thread.
 */
internal object ActiveTripStopTimelineResolver {
    private val zoneId = ZoneId.of("Europe/Moscow")

    fun resolve(context: Context, leg: RouteLeg): List<ActiveTripResolvedStop> {
        if (leg.mode == TransportMode.WALK) return emptyList()
        if (BuildConfig.DEBUG && leg.lineId?.startsWith("qa:") == true) return resolveQa(leg)
        return runCatching {
            when (leg.mode) {
                TransportMode.BUS, TransportMode.TRAM -> resolveSurface(context, leg)
                TransportMode.METRO, TransportMode.MCC -> resolveStaticRail(context, leg)
                TransportMode.MCD, TransportMode.TRAIN -> resolvePublishedRail(context, leg)
                TransportMode.WALK -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    private fun resolveSurface(context: Context, leg: RouteLeg): List<ActiveTripResolvedStop> {
        val routeId = leg.lineId ?: return emptyList()
        val fromId = leg.from.id.removePrefix("stop:").toIntOrNull() ?: return emptyList()
        val toId = leg.to.id.removePrefix("stop:").toIntOrNull() ?: return emptyList()
        val serviceMidnight = Instant.ofEpochSecond(leg.departureEpochSec)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toEpochSecond()
        val expectedDeparture = (leg.departureEpochSec - serviceMidnight).toInt()
        val expectedDuration = (leg.arrivalEpochSec - leg.departureEpochSec).toInt().coerceAtLeast(1)
        val start = (expectedDeparture - 2).coerceAtLeast(0)
        val end = (expectedDeparture + expectedDuration + 15 * 60).coerceAtMost(30 * 60 * 60)

        SurfaceScheduleRepository(context).use { repository ->
            val stops = repository.loadStops().associateBy { it.id }
            val chain = ArrayList<SurfaceConnection>()
            var tripId: String? = null
            var firstSequence = Int.MIN_VALUE

            repository.scanConnections(start, end) { connection ->
                if (tripId == null) {
                    val matchesStart = connection.routeId == routeId &&
                        connection.fromStopId == fromId &&
                        abs(connection.departureSec - expectedDeparture) <= 2
                    if (!matchesStart) return@scanConnections true
                    tripId = connection.tripId
                    firstSequence = connection.sequence
                }
                if (connection.tripId == tripId && connection.sequence >= firstSequence) {
                    if (chain.isEmpty() || connection.sequence > chain.last().sequence) chain += connection
                    if (connection.toStopId == toId) return@scanConnections false
                }
                true
            }

            if (chain.isEmpty() || chain.first().fromStopId != fromId || chain.last().toStopId != toId) {
                return emptyList()
            }
            val firstStop = stops[chain.first().fromStopId] ?: return emptyList()
            val firstDeparture = chain.first().departureSec
            val result = ArrayList<ActiveTripResolvedStop>(chain.size + 1)
            result += ActiveTripResolvedStop(firstStop.name, leg.departureEpochSec, leg.departureEpochSec)
            for (index in chain.indices) {
                val connection = chain[index]
                val stop = stops[connection.toStopId] ?: return emptyList()
                val arrival = leg.departureEpochSec + (connection.arrivalSec - firstDeparture)
                val next = chain.getOrNull(index + 1)
                val departure = if (next?.fromStopId == connection.toStopId) {
                    leg.departureEpochSec + (next.departureSec - firstDeparture)
                } else {
                    arrival
                }
                result += ActiveTripResolvedStop(stop.name, arrival, departure.coerceAtLeast(arrival))
            }
            return result.validatedAgainst(leg)
        }
    }

    private fun resolveStaticRail(context: Context, leg: RouteLeg): List<ActiveTripResolvedStop> {
        val lineId = leg.lineId ?: return emptyList()
        val fromId = leg.from.id.removePrefix("rail:")
        val toId = leg.to.id.removePrefix("rail:")
        if (fromId == leg.from.id || toId == leg.to.id) return emptyList()
        val graph = File(context.filesDir, "runtime/rail/graph.json")
        if (!graph.isFile) return emptyList()
        val root = JSONObject(graph.readText())
        val routes = root.getJSONArray("routes")
        for (routeIndex in 0 until routes.length()) {
            val route = routes.getJSONObject(routeIndex)
            if (!route.optBoolean("routeable", false)) continue
            val mode = TransportMode.fromRuntimeValue(route.optString("mode")) ?: continue
            if (mode != leg.mode) continue
            val relationId = route.optString("osm_relation_id")
            if ("${mode.name}:$relationId" != lineId) continue

            val stops = route.getJSONArray("stops")
            val segments = route.getJSONArray("segment_seconds")
            var fromIndex = -1
            var toIndex = -1
            for (index in 0 until stops.length()) {
                val id = stops.getJSONObject(index).optString("osm_stop_id")
                if (id == fromId && fromIndex < 0) fromIndex = index
                if (fromIndex >= 0 && id == toId) {
                    toIndex = index
                    break
                }
            }
            if (fromIndex < 0 || toIndex <= fromIndex || segments.length() < toIndex) continue

            var epoch = leg.departureEpochSec
            val resolved = ArrayList<ActiveTripResolvedStop>(toIndex - fromIndex + 1)
            resolved += ActiveTripResolvedStop(
                stops.getJSONObject(fromIndex).optString("name", leg.from.name),
                epoch,
                epoch
            )
            for (index in fromIndex until toIndex) {
                epoch += segments.getInt(index).coerceAtLeast(1)
                val stop = stops.getJSONObject(index + 1)
                resolved += ActiveTripResolvedStop(stop.optString("name", stop.optString("osm_stop_id")), epoch, epoch)
            }
            return resolved.rebasedToArrival(leg).validatedAgainst(leg)
        }
        return emptyList()
    }

    private fun resolvePublishedRail(context: Context, leg: RouteLeg): List<ActiveTripResolvedStop> {
        val tripId = leg.lineId ?: return emptyList()
        val fromId = leg.from.id.removePrefix("rail-timetable:").toIntOrNull() ?: return emptyList()
        val toId = leg.to.id.removePrefix("rail-timetable:").toIntOrNull() ?: return emptyList()
        val runtime = File(context.filesDir, "runtime/rail/timetable.json")
        val text = if (runtime.isFile) {
            runtime.readText()
        } else {
            context.assets.open("rail_timetable_mtppk_2026-04-27.json").bufferedReader().use { it.readText() }
        }
        val root = JSONObject(text)
        val stationItems = root.getJSONArray("stations")
        val stationNames = HashMap<Int, String>(stationItems.length())
        for (index in 0 until stationItems.length()) {
            val station = stationItems.getJSONObject(index)
            stationNames[station.getInt("id")] = station.optString("name", station.getInt("id").toString())
        }
        val trips = root.getJSONArray("trips")
        for (tripIndex in 0 until trips.length()) {
            val trip = trips.getJSONObject(tripIndex)
            if (trip.optString("id") != tripId) continue
            val stopItems = trip.getJSONArray("stops")
            var boardIndex = -1
            var alightIndex = -1
            for (index in 0 until stopItems.length()) {
                val id = stopItems.getJSONArray(index).getInt(0)
                if (id == fromId && boardIndex < 0) boardIndex = index
                if (boardIndex >= 0 && id == toId) {
                    alightIndex = index
                    break
                }
            }
            if (boardIndex < 0 || alightIndex <= boardIndex) return emptyList()
            val boardServiceSec = stopItems.getJSONArray(boardIndex).getInt(1)
            val result = ArrayList<ActiveTripResolvedStop>(alightIndex - boardIndex + 1)
            for (index in boardIndex..alightIndex) {
                val values = stopItems.getJSONArray(index)
                val stationId = values.getInt(0)
                val epoch = leg.departureEpochSec + (values.getInt(1) - boardServiceSec)
                result += ActiveTripResolvedStop(stationNames[stationId] ?: stationId.toString(), epoch, epoch)
            }
            return result.rebasedToArrival(leg).validatedAgainst(leg)
        }
        return emptyList()
    }

    /** Debug-only deterministic data to exercise the visual component; never used by product routes. */
    private fun resolveQa(leg: RouteLeg): List<ActiveTripResolvedStop> {
        val names = when (leg.lineId) {
            "qa:m2" -> listOf(
                leg.from.name,
                "Охотный Ряд",
                "Манежная площадь",
                "Кузнецкий Мост",
                "Лубянская площадь",
                leg.to.name
            )
            "qa:6" -> listOf(
                leg.from.name,
                "Тургеневская",
                "Сухаревская",
                "Проспект Мира",
                "Рижская",
                "Алексеевская",
                "ВДНХ",
                leg.to.name
            )
            else -> return emptyList()
        }
        val duration = (leg.arrivalEpochSec - leg.departureEpochSec).coerceAtLeast(names.size.toLong())
        return names.mapIndexed { index, name ->
            val fraction = index.toDouble() / names.lastIndex.coerceAtLeast(1).toDouble()
            val epoch = leg.departureEpochSec + (duration.toDouble() * fraction).toLong()
            ActiveTripResolvedStop(name, epoch, epoch)
        }.rebasedToArrival(leg)
    }

    private fun List<ActiveTripResolvedStop>.rebasedToArrival(leg: RouteLeg): List<ActiveTripResolvedStop> {
        if (size < 2) return emptyList()
        val rawStart = first().departureEpochSec
        val rawDuration = last().arrivalEpochSec - rawStart
        val targetDuration = leg.arrivalEpochSec - leg.departureEpochSec
        if (rawDuration <= 0 || targetDuration <= 0 || abs(rawDuration - targetDuration) <= 2L) return this
        return map { stop ->
            val relativeArrival = stop.arrivalEpochSec - rawStart
            val relativeDeparture = stop.departureEpochSec - rawStart
            val arrival = leg.departureEpochSec + relativeArrival * targetDuration / rawDuration
            val departure = leg.departureEpochSec + relativeDeparture * targetDuration / rawDuration
            stop.copy(arrivalEpochSec = arrival, departureEpochSec = departure.coerceAtLeast(arrival))
        }
    }

    private fun List<ActiveTripResolvedStop>.validatedAgainst(leg: RouteLeg): List<ActiveTripResolvedStop> {
        if (size < 2 || size - 1 != leg.stopCount) return emptyList()
        if (first().name.isBlank() || last().name.isBlank()) return emptyList()
        if (first().departureEpochSec < leg.departureEpochSec - 2L) return emptyList()
        if (last().arrivalEpochSec > leg.arrivalEpochSec + 2L) return emptyList()
        if (zipWithNext().any { (a, b) -> b.arrivalEpochSec < a.departureEpochSec }) return emptyList()
        return this
    }
}

package app.humanrouter.routing

import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Cheap spatial index over routeable METRO/MCC stop positions in rail/graph.json. */
internal class RailWaypointIndex private constructor(graphFile: File) {
    data class Waypoint(
        val id: String,
        val name: String,
        val point: GeoPoint
    )

    private val waypoints: List<Waypoint>

    init {
        val byName = LinkedHashMap<String, Waypoint>()
        val routes = JSONObject(graphFile.readText()).getJSONArray("routes")
        for (routeIndex in 0 until routes.length()) {
            val route = routes.getJSONObject(routeIndex)
            if (!route.optBoolean("routeable", false)) continue
            if (route.optString("mode") !in setOf("METRO", "MCC")) continue
            val stops = route.getJSONArray("stops")
            for (i in 0 until stops.length()) {
                val stop = stops.getJSONObject(i)
                val name = stop.optString("name").trim()
                if (name.isBlank()) continue
                val key = normalizeName(name)
                if (key.isBlank()) continue
                byName.putIfAbsent(
                    key,
                    Waypoint(
                        id = "rail:${stop.getString("osm_stop_id")}",
                        name = name,
                        point = GeoPoint(stop.getDouble("lat"), stop.getDouble("lon"))
                    )
                )
            }
        }
        waypoints = byName.values.toList()
    }

    fun nearest(
        point: GeoPoint,
        maxMeters: Int,
        limit: Int
    ): List<Waypoint> = waypoints.asSequence()
        .map { it to haversineMeters(point, it.point) }
        .filter { it.second <= maxMeters }
        .sortedBy { it.second }
        .take(limit)
        .map { it.first }
        .toList()

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

        fun openOrNull(runtimeRoot: File): RailWaypointIndex? {
            val graph = File(runtimeRoot, "rail/graph.json")
            if (!graph.exists()) return null
            return runCatching { RailWaypointIndex(graph) }.getOrNull()
        }
    }
}

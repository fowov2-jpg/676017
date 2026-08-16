package app.humanrouter.routing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class RailGraphRouterExitTest {
    @Test
    fun egressUsesNearestPublishedMetroExitAndKeepsItsNumber() {
        val runtimeRoot = Files.createTempDirectory("rail-exit-test").toFile()
        try {
            val railDir = File(runtimeRoot, "rail").apply { mkdirs() }
            File(railDir, "graph.json").writeText(graphJson().toString())

            val router = RailGraphRouter.openOrNull(runtimeRoot, RoutePreferences(), walkGraph = null)
            assertNotNull(router)

            val route = router!!.findFastest(
                origin = GeoPoint(55.7503, 37.6002),
                destination = GeoPoint(55.8011, 37.7009),
                departureEpochSec = Instant.parse("2026-08-15T09:00:00Z").epochSecond
            )
            assertNotNull(route)

            val walkLegs = route!!.legs.filter { it.mode == TransportMode.WALK }
            assertTrue(walkLegs.size >= 2)
            val egress = walkLegs.last()
            assertEquals("metro-exit:exit-b-7", egress.from.id)
            assertTrue(egress.from.name.startsWith("Выход 7 · Станция Б"))
            assertEquals(55.8010, egress.geometry.first().lat, 0.00001)
            assertEquals(37.7010, egress.geometry.first().lon, 0.00001)
        } finally {
            runtimeRoot.deleteRecursively()
        }
    }

    @Test
    fun missingExitRefIsNeverInvented() {
        val runtimeRoot = Files.createTempDirectory("rail-exit-no-ref-test").toFile()
        try {
            val railDir = File(runtimeRoot, "rail").apply { mkdirs() }
            val graph = graphJson()
            graph.getJSONArray("exits").getJSONObject(1).remove("ref")
            File(railDir, "graph.json").writeText(graph.toString())

            val route = RailGraphRouter.openOrNull(runtimeRoot, RoutePreferences(), walkGraph = null)!!
                .findFastest(
                    origin = GeoPoint(55.7503, 37.6002),
                    destination = GeoPoint(55.8011, 37.7009),
                    departureEpochSec = Instant.parse("2026-08-15T09:00:00Z").epochSecond
                )!!

            val egress = route.legs.last { it.mode == TransportMode.WALK }
            assertEquals("metro-exit:exit-b-7", egress.from.id)
            assertEquals("Выход · Станция Б", egress.from.name)
            assertTrue(!egress.from.name.contains(" 7 "))
        } finally {
            runtimeRoot.deleteRecursively()
        }
    }

    private fun graphJson(): JSONObject {
        fun stop(id: String, name: String, lat: Double, lon: Double) = JSONObject()
            .put("osm_stop_id", id)
            .put("name", name)
            .put("lat", lat)
            .put("lon", lon)

        val route = JSONObject()
            .put("osm_relation_id", "metro-test-6")
            .put("mode", "METRO")
            .put("ref", "6")
            .put("name", "Калужско-Рижская линия")
            .put("display_line_name", "6 · Калужско-Рижская линия")
            .put("routeable", true)
            .put("timing_confidence", 0.8)
            .put(
                "stops",
                JSONArray()
                    .put(stop("a", "Станция А", 55.7500, 37.6000))
                    .put(stop("b", "Станция Б", 55.8000, 37.7000))
            )
            .put("segment_seconds", JSONArray().put(300))

        val exitA = JSONObject()
            .put("osm_id", "exit-a-1")
            .put("station_name", "Станция А")
            .put("ref", "1")
            .put("lat", 55.7502)
            .put("lon", 37.6001)

        val exitB = JSONObject()
            .put("osm_id", "exit-b-7")
            .put("station_name", "Станция Б")
            .put("ref", "7")
            .put("lat", 55.8010)
            .put("lon", 37.7010)

        // Deliberately farther from the destination: the router must not simply take the first exit.
        val exitBFar = JSONObject()
            .put("osm_id", "exit-b-2")
            .put("station_name", "Станция Б")
            .put("ref", "2")
            .put("lat", 55.7970)
            .put("lon", 37.6960)

        return JSONObject()
            .put("schema", 2)
            .put("routes", JSONArray().put(route))
            .put("exits", JSONArray().put(exitA).put(exitB).put(exitBFar))
    }
}

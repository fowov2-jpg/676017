package app.humanrouter.routing

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailTimetableRouterTest {
    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun publishedMcdAndTrainDeparturesProduceRealTimedLegs() {
        val router = RailTimetableRouter.fromJsonForTest(sampleTimetable(), zoneId = zone)
        val departure = LocalDate.of(2026, 8, 15).atTime(9, 50).atZone(zone).toEpochSecond()

        val routes = router.findCandidates(
            origin = GeoPoint(55.7500, 37.6100),
            destination = GeoPoint(55.7600, 37.6200),
            departureEpochSec = departure,
            broadSearch = true
        )

        assertTrue(routes.any { route -> route.legs.any { it.mode == TransportMode.MCD } })
        assertTrue(routes.any { route -> route.legs.any { it.mode == TransportMode.TRAIN } })
        val mcd = routes.first { route -> route.legs.any { it.mode == TransportMode.MCD } }
        val ride = mcd.legs.first { it.mode == TransportMode.MCD }
        assertEquals("D3 · 7201", ride.lineName)
        assertEquals(2, ride.stopCount)
        assertEquals(3, ride.geometry.size)
        assertEquals(GeoPoint(55.7550, 37.6150), ride.geometry[1])
        assertTrue(ride.waitSeconds > 0)
    }

    @Test
    fun topologyOnlyMcdRelationIsNotRouteableWithoutTimetable() {
        val root = File("build/tmp/vh-mcd-topology-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val rail = root.resolve("rail").apply { mkdirs() }
            rail.resolve("graph.json").writeText(
                """{"routes":[{"routeable":true,"mode":"MCD","osm_relation_id":"1","ref":"D3","timing_confidence":0.5,"stops":[{"osm_stop_id":"a","name":"A","lat":55.75,"lon":37.61},{"osm_stop_id":"b","name":"B","lat":55.76,"lon":37.62}],"segment_seconds":[600]}]}"""
            )
            val router = RailGraphRouter.openOrNull(root, RoutePreferences(), null)
            requireNotNull(router)
            assertTrue(
                router.findFastest(
                    GeoPoint(55.7500, 37.6100),
                    GeoPoint(55.7600, 37.6200),
                    LocalDate.of(2026, 8, 15).atTime(10, 0).atZone(zone).toEpochSecond()
                ) == null
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun timetableIsNeverAppliedBeforeItsEffectiveDate() {
        val router = RailTimetableRouter.fromJsonForTest(sampleTimetable(), zoneId = zone)
        val departure = LocalDate.of(2026, 4, 25).atTime(9, 50).atZone(zone).toEpochSecond()

        val routes = router.findCandidates(
            origin = GeoPoint(55.7500, 37.6100),
            destination = GeoPoint(55.7600, 37.6200),
            departureEpochSec = departure,
            broadSearch = true
        )

        assertTrue(routes.isEmpty())
    }

    private fun sampleTimetable(): String = """
        {
          "schema": 1,
          "effective_from": "2026-04-27",
          "coverage": "test",
          "limitations": "test",
          "stations": [
            {"id": 0, "name": "A", "lat": 55.7500, "lon": 37.6100},
            {"id": 1, "name": "B", "lat": 55.7550, "lon": 37.6150},
            {"id": 2, "name": "C", "lat": 55.7600, "lon": 37.6200}
          ],
          "trips": [
            {"id": "mtppk:mcd", "mode": "MCD", "number": "7201", "service": "published_default", "stops": [[0,36000,1,1],[1,36300,1,1],[2,36600,1,1]]},
            {"id": "mtppk:train", "mode": "TRAIN", "number": "6501", "service": "published_default", "stops": [[0,36300,1,1],[1,36600,1,1],[2,36900,1,1]]}
          ]
        }
    """.trimIndent()
}

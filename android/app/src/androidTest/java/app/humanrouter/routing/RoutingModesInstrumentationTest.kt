package app.humanrouter.routing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.humanrouter.VremyaHodomApp
import app.humanrouter.transit.NearbyRepository
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutingModesInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<VremyaHodomApp>()
    private val runtimeRoot = File(context.filesDir, "runtime")
    private val zone = ZoneId.of("Europe/Moscow")
    private val departure = LocalDate.of(2026, 8, 15).atTime(10, 0).atZone(zone).toEpochSecond()

    @Before
    fun installSyntheticRuntime() {
        runtimeRoot.deleteRecursively()
        buildSurfaceRuntime()
        buildRailRuntime()
    }

    @After
    fun removeSyntheticRuntime() {
        runtimeRoot.deleteRecursively()
    }

    @Test
    fun everyDeclaredModeParticipatesWithoutModeSubstitution() {
        assertHasModes(plan(ORIGIN, METRO_A), TransportMode.BUS)
        assertHasModes(plan(METRO_B, DESTINATION), TransportMode.TRAM)
        assertHasModes(plan(METRO_A, METRO_B), TransportMode.METRO)
        assertHasModes(plan(MCC_A, MCC_B), TransportMode.MCC)
        assertHasModes(plan(MCD_A, MCD_B), TransportMode.MCD)
        assertHasModes(plan(TRAIN_A, TRAIN_B), TransportMode.TRAIN)
        assertTrue(plan(WALK_A, WALK_B).any { route -> route.legs.all { it.mode == TransportMode.WALK } })
    }

    @Test
    fun threeRealCombinedSearchesPreserveStageArrivalTimes() {
        assertHasOrderedModes(plan(ORIGIN, METRO_B), TransportMode.BUS, TransportMode.METRO)
        assertHasOrderedModes(plan(METRO_A, DESTINATION), TransportMode.METRO, TransportMode.TRAM)
        assertHasOrderedModes(
            plan(ORIGIN, DESTINATION),
            TransportMode.BUS,
            TransportMode.METRO,
            TransportMode.TRAM
        )
    }

    @Test
    fun nearbyRailUsesPublishedMcdAndTrainTimetableWithoutFakeEta() {
        val places = NearbyRepository(context, zone).findNearby(MCD_A, departure, limit = 10)
        val station = places.first { place ->
            TransportMode.MCD in place.modes && TransportMode.TRAIN in place.modes
        }
        assertTrue("published rail departure is missing", station.nextDepartureEpochSec != null)
        assertTrue("MCD route label is missing", "D3" in station.routeLabels)
    }

    private fun plan(origin: GeoPoint, destination: GeoPoint): List<RouteCandidate> {
        val result = HumanRouterEngine(context, zoneId = zone)
            .planOptions(origin, destination, departure)
        require(result is HumanRouterEngine.PlanResult.Success) { "unexpected plan result: $result" }
        return result.routes.map { it.route }
    }

    private fun assertHasModes(routes: List<RouteCandidate>, vararg required: TransportMode) {
        assertTrue(
            "required modes ${required.toList()} missing from ${routes.map(::transitModes)}",
            routes.any { route -> required.all { it in route.legs.map(RouteLeg::mode) } }
        )
    }

    private fun assertHasOrderedModes(routes: List<RouteCandidate>, vararg required: TransportMode) {
        assertTrue(
            "required sequence ${required.toList()} missing from ${routes.map(::transitModes)}",
            routes.any { route ->
                val modes = transitModes(route)
                containsOrdered(modes, required.toList()) && route.legs.zipWithNext().all { (first, second) ->
                    second.departureEpochSec >= first.arrivalEpochSec
                }
            }
        )
    }

    private fun transitModes(route: RouteCandidate): List<TransportMode> = route.legs
        .map(RouteLeg::mode)
        .filter { it != TransportMode.WALK }

    private fun containsOrdered(actual: List<TransportMode>, required: List<TransportMode>): Boolean {
        var cursor = 0
        for (mode in required) {
            var found = false
            while (cursor < actual.size) {
                if (actual[cursor++] == mode) {
                    found = true
                    break
                }
            }
            if (!found) return false
        }
        return true
    }

    private fun buildSurfaceRuntime() {
        val surface = File(runtimeRoot, "surface").apply { mkdirs() }
        File(surface, "manifest.json").writeText(
            """{"primary_file":"surface.sqlite","service_date":"2026-08-15"}"""
        )
        val database = SQLiteDatabase.openOrCreateDatabase(File(surface, "surface.sqlite"), null)
        database.use { db ->
            db.execSQL(
                "CREATE TABLE stops (stop_id INTEGER PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, transport_type TEXT)"
            )
            db.execSQL(
                "CREATE TABLE routes (route_id TEXT PRIMARY KEY, short_name TEXT, long_name TEXT, route_mode TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE connections (dep INTEGER NOT NULL, from_stop INTEGER NOT NULL, trip_id TEXT NOT NULL, seq INTEGER NOT NULL, to_stop INTEGER NOT NULL, arr INTEGER NOT NULL, route_id TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX connections_departure ON connections(dep)")

            insertStop(db, 1, "Автобусная A", ORIGIN, "BUS")
            insertStop(db, 2, "Автобусная B", METRO_A, "BUS")
            insertStop(db, 3, "Трамвайная A", METRO_B, "TRAM")
            insertStop(db, 4, "Трамвайная B", DESTINATION, "TRAM")
            insertRoute(db, "bus-1", "Б1", "BUS")
            insertRoute(db, "tram-1", "Т1", "TRAM")
            insertConnection(db, 36_120, 1, "bus-trip", 0, 2, 36_600, "bus-1")
            insertConnection(db, 36_120, 3, "tram-early", 0, 4, 36_480, "tram-1")
            insertConnection(db, 37_380, 3, "tram-late", 0, 4, 37_860, "tram-1")
        }
    }

    private fun buildRailRuntime() {
        val rail = File(runtimeRoot, "rail").apply { mkdirs() }
        File(rail, "graph.json").writeText(
            """
            {
              "schema": 1,
              "routes": [
                {
                  "osm_relation_id": "metro-1",
                  "mode": "METRO",
                  "ref": "М1",
                  "routeable": true,
                  "timing_confidence": 0.8,
                  "stops": [
                    {"osm_stop_id":"metro-a","name":"Метро A","lat":${METRO_A.lat},"lon":${METRO_A.lon}},
                    {"osm_stop_id":"metro-b","name":"Метро B","lat":${METRO_B.lat},"lon":${METRO_B.lon}}
                  ],
                  "segment_seconds": [360]
                },
                {
                  "osm_relation_id": "mcc-1",
                  "mode": "MCC",
                  "ref": "МЦК",
                  "routeable": true,
                  "timing_confidence": 0.75,
                  "stops": [
                    {"osm_stop_id":"mcc-a","name":"МЦК A","lat":${MCC_A.lat},"lon":${MCC_A.lon}},
                    {"osm_stop_id":"mcc-b","name":"МЦК B","lat":${MCC_B.lat},"lon":${MCC_B.lon}}
                  ],
                  "segment_seconds": [420]
                }
              ]
            }
            """.trimIndent()
        )
    }

    private fun insertStop(
        database: SQLiteDatabase,
        id: Int,
        name: String,
        point: GeoPoint,
        mode: String
    ) {
        database.insertOrThrow(
            "stops",
            null,
            ContentValues().apply {
                put("stop_id", id)
                put("name", name)
                put("lat", point.lat)
                put("lon", point.lon)
                put("transport_type", mode)
            }
        )
    }

    private fun insertRoute(database: SQLiteDatabase, id: String, shortName: String, mode: String) {
        database.insertOrThrow(
            "routes",
            null,
            ContentValues().apply {
                put("route_id", id)
                put("short_name", shortName)
                put("long_name", shortName)
                put("route_mode", mode)
            }
        )
    }

    private fun insertConnection(
        database: SQLiteDatabase,
        departure: Int,
        from: Int,
        trip: String,
        sequence: Int,
        to: Int,
        arrival: Int,
        route: String
    ) {
        database.insertOrThrow(
            "connections",
            null,
            ContentValues().apply {
                put("dep", departure)
                put("from_stop", from)
                put("trip_id", trip)
                put("seq", sequence)
                put("to_stop", to)
                put("arr", arrival)
                put("route_id", route)
            }
        )
    }

    private companion object {
        val ORIGIN = GeoPoint(55.7000, 37.5000)
        val METRO_A = GeoPoint(55.7000, 37.5300)
        val METRO_B = GeoPoint(55.7000, 37.5650)
        val DESTINATION = GeoPoint(55.7000, 37.6000)
        val MCC_A = GeoPoint(55.8000, 37.5000)
        val MCC_B = GeoPoint(55.8000, 37.5600)
        val MCD_A = GeoPoint(55.9800977, 37.1737339)
        val MCD_B = GeoPoint(55.8175476, 37.6033151)
        val TRAIN_A = GeoPoint(55.9800977, 37.1737339)
        val TRAIN_B = GeoPoint(55.7762115, 37.6515580)
        val WALK_A = GeoPoint(55.9000, 37.7000)
        val WALK_B = GeoPoint(55.9005, 37.7005)
    }
}

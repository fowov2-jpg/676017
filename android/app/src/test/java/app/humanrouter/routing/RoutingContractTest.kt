package app.humanrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingContractTest {
    private val a = RoutePlace("a", "A", GeoPoint(55.75, 37.61))
    private val b = RoutePlace("b", "B", GeoPoint(55.76, 37.62))

    @Test
    fun runtimeModeMappingIsStrictAndSupportsDeclaredRailModes() {
        assertEquals(TransportMode.BUS, TransportMode.fromRuntimeValue("bus"))
        assertEquals(TransportMode.MCD, TransportMode.fromRuntimeValue("MCD"))
        assertEquals(TransportMode.TRAIN, TransportMode.fromRuntimeValue(" train "))
        assertNull(TransportMode.fromRuntimeValue("SCOOTER"))
        assertNull(TransportMode.fromRuntimeValue(null))
    }

    @Test
    fun metroServiceWindowHonorsBothBoundaries() {
        assertTrue(RailServiceWindow.acceptsMetroSecondOfDay(seconds(0, 59, 59)))
        assertFalse(RailServiceWindow.acceptsMetroSecondOfDay(seconds(1, 0, 0)))
        assertFalse(RailServiceWindow.acceptsMetroSecondOfDay(seconds(5, 29, 59)))
        assertTrue(RailServiceWindow.acceptsMetroSecondOfDay(seconds(5, 30, 0)))
        assertTrue(RailServiceWindow.acceptsMetroSecondOfDay(seconds(23, 59, 59)))
    }

    @Test
    fun earlyMetroWindowCarriesUncertaintyOnlyUntilSixOhFive() {
        assertEquals(
            RailServiceWindow.METRO_EARLY_SERVICE_UNCERTAINTY_SECONDS,
            RailServiceWindow.metroBoundaryUncertaintyAtSecondOfDay(seconds(5, 30, 0))
        )
        assertEquals(
            RailServiceWindow.METRO_EARLY_SERVICE_UNCERTAINTY_SECONDS,
            RailServiceWindow.metroBoundaryUncertaintyAtSecondOfDay(seconds(6, 4, 59))
        )
        assertEquals(0, RailServiceWindow.metroBoundaryUncertaintyAtSecondOfDay(seconds(6, 5, 0)))
    }

    @Test
    fun filtersChangeTheReturnedSetAndOrdering() {
        val start = 10_000L
        val walk = ranked("walk", TransportMode.WALK, start + 900, walkMeters = 650)
        val bus = ranked("bus", TransportMode.BUS, start + 700, walkMeters = 250)
        val metro = ranked("metro", TransportMode.METRO, start + 800, walkMeters = 100)
        val routes = listOf(walk, bus, metro)

        assertEquals(listOf("bus", "metro", "walk"), ids(RouteFilters.apply(routes, RouteFilter.FASTEST)))
        assertEquals(listOf("metro", "bus", "walk"), ids(RouteFilters.apply(routes, RouteFilter.LESS_WALKING)))
        assertEquals(listOf("metro"), ids(RouteFilters.apply(routes, RouteFilter.METRO)))
        assertEquals(listOf("bus"), ids(RouteFilters.apply(routes, RouteFilter.SURFACE)))
    }

    private fun ranked(id: String, mode: TransportMode, arrival: Long, walkMeters: Int): RankedRoute {
        val route = RouteCandidate(
            id = id,
            requestedDepartureEpochSec = 10_000L,
            legs = listOf(
                RouteLeg(
                    mode = mode,
                    from = a,
                    to = b,
                    departureEpochSec = 10_000L,
                    arrivalEpochSec = arrival,
                    walkMeters = walkMeters,
                    realtimeConfidence = 0.8
                )
            )
        )
        return RankedRoute(route, RouteObjective.FASTEST, arrival, arrival, 1.0, arrival.toDouble())
    }

    private fun ids(routes: List<RankedRoute>): List<String> = routes.map { it.route.id }

    private fun seconds(hour: Int, minute: Int, second: Int): Int = hour * 3_600 + minute * 60 + second
}

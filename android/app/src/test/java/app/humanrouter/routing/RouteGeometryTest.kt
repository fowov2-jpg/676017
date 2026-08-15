package app.humanrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    private val a = RoutePlace("a", "A", GeoPoint(55.70, 37.50))
    private val b = RoutePlace("b", "B", GeoPoint(55.75, 37.60))
    private val c = RoutePlace("c", "C", GeoPoint(55.80, 37.70))

    @Test
    fun routeUsesDetailedLegGeometryInsteadOfOneEndpointChord() {
        val busMidpoint = GeoPoint(55.73, 37.58)
        val railMidpoint = GeoPoint(55.78, 37.66)
        val route = RouteCandidate(
            id = "detailed",
            requestedDepartureEpochSec = 1_000,
            legs = listOf(
                RouteLeg(
                    mode = TransportMode.BUS,
                    from = a,
                    to = b,
                    departureEpochSec = 1_000,
                    arrivalEpochSec = 1_600,
                    geometry = listOf(a.point, busMidpoint, b.point)
                ),
                RouteLeg(
                    mode = TransportMode.METRO,
                    from = b,
                    to = c,
                    departureEpochSec = 1_700,
                    arrivalEpochSec = 2_200,
                    geometry = listOf(b.point, railMidpoint, c.point)
                )
            )
        )

        assertEquals(listOf(a.point, busMidpoint, b.point, railMidpoint, c.point), route.mapPoints())
    }

    @Test
    fun malformedMissingEndpointsAreNormalizedForRendering() {
        val midpoint = GeoPoint(55.74, 37.57)
        val leg = RouteLeg(
            mode = TransportMode.WALK,
            from = a,
            to = b,
            departureEpochSec = 1_000,
            arrivalEpochSec = 1_300,
            walkMeters = 350,
            geometry = listOf(midpoint, b.point)
        )

        assertEquals(a.point, leg.mapPoints().first())
        assertEquals(b.point, leg.mapPoints().last())
        assertTrue(midpoint in leg.mapPoints())
    }
}

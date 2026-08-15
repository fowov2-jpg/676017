package app.humanrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutePresentationTest {
    private val a = RoutePlace("a", "Откуда", GeoPoint(55.0, 37.0))
    private val station = RoutePlace("station", "Медведково", GeoPoint(55.1, 37.1))
    private val platform = RoutePlace("platform", "Медведково", GeoPoint(55.1001, 37.1001))
    private val interchange = RoutePlace("interchange", "Китай-город", GeoPoint(55.2, 37.2))
    private val b = RoutePlace("b", "Куда", GeoPoint(55.3, 37.3))

    @Test
    fun adjacentWalksAreGroupedAndEndpointNamesAreReplaced() {
        val route = candidate(
            RouteLeg(TransportMode.WALK, a, station, 0, 180, walkMeters = 134),
            RouteLeg(TransportMode.WALK, station, platform, 180, 240, walkMeters = 18),
            RouteLeg(TransportMode.METRO, platform, interchange, 240, 840, lineId = "6", stopCount = 4),
            RouteLeg(TransportMode.WALK, interchange, b, 840, 900, walkMeters = 55)
        )

        val steps = RoutePresentation.steps(route, "Угрешская", "Кузьминки")

        assertEquals(3, steps.size)
        assertEquals(RouteDisplayKind.WALK, steps[0].kind)
        assertEquals(152, steps[0].walkMeters)
        assertEquals(2, steps[0].sourceLegCount)
        assertEquals("Угрешская", steps.first().from.name)
        assertEquals("Кузьминки", steps.last().to.name)
    }

    @Test
    fun zeroMetreWalkBetweenLinesBecomesTransfer() {
        val route = candidate(
            RouteLeg(TransportMode.METRO, station, interchange, 0, 600, lineId = "6", stopCount = 5),
            RouteLeg(TransportMode.WALK, interchange, interchange, 600, 900, walkMeters = 0),
            RouteLeg(TransportMode.METRO, interchange, b, 900, 1_500, lineId = "7", stopCount = 4)
        )

        val steps = RoutePresentation.steps(route)

        assertEquals(RouteDisplayKind.TRANSFER, steps[1].kind)
        assertEquals(0, steps[1].walkMeters)
        assertFalse(steps.any { it.kind == RouteDisplayKind.WALK && it.walkMeters == 0 })
    }

    @Test
    fun zeroMetreTechnicalLegAtDestinationIsRemoved() {
        val route = candidate(
            RouteLeg(TransportMode.METRO, station, b, 0, 600, lineId = "7", stopCount = 4),
            RouteLeg(TransportMode.WALK, b, b, 600, 720, walkMeters = 0)
        )

        val steps = RoutePresentation.steps(route)

        assertEquals(1, steps.size)
        assertEquals(RouteDisplayKind.TRANSIT, steps.single().kind)
    }

    private fun candidate(vararg legs: RouteLeg) = RouteCandidate(
        id = "test",
        requestedDepartureEpochSec = legs.first().departureEpochSec,
        legs = legs.toList()
    )
}

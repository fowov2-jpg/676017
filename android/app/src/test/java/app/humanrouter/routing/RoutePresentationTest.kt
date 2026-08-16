package app.humanrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals("Пешком 152 м", steps[0].instruction)
        assertEquals("Угрешская", steps.first().from.name)
        assertEquals("Кузьминки", steps.last().to.name)
    }

    @Test
    fun zeroMetreWalkBetweenLinesBecomesNeutralInterchange() {
        val route = candidate(
            RouteLeg(TransportMode.METRO, station, interchange, 0, 600, lineId = "6", stopCount = 5),
            RouteLeg(TransportMode.WALK, interchange, interchange, 600, 900, walkMeters = 0),
            RouteLeg(TransportMode.METRO, interchange, b, 900, 1_500, lineId = "7", stopCount = 4)
        )

        val steps = RoutePresentation.steps(route)

        assertEquals(RouteDisplayKind.TRANSFER, steps[1].kind)
        assertEquals(RouteTransferKind.INTERCHANGE, steps[1].transferKind)
        assertEquals("Пересадка", steps[1].instruction)
        assertEquals(0, steps[1].walkMeters)
        assertFalse(steps.any { it.kind == RouteDisplayKind.WALK && it.walkMeters == 0 })
    }

    @Test
    fun realMetroExitIsExposedAsPedestrianInstruction() {
        val exit = RoutePlace(
            "metro-exit:12345",
            "Выход 7 · Медведково",
            GeoPoint(55.887, 37.661)
        )
        val destination = RoutePlace("dest", "Широкая улица, 12", GeoPoint(55.889, 37.665))
        val route = candidate(
            RouteLeg(
                TransportMode.METRO,
                station,
                station,
                0,
                600,
                lineId = "METRO:6",
                lineName = "6 · Калужско-Рижская линия",
                stopCount = 5
            ),
            RouteLeg(
                TransportMode.WALK,
                exit,
                destination,
                600,
                840,
                walkMeters = 310,
                geometry = listOf(exit.point, GeoPoint(55.888, 37.663), destination.point)
            )
        )

        val steps = RoutePresentation.steps(route)

        assertEquals(2, steps.size)
        val walk = steps[1]
        assertEquals(RouteDisplayKind.WALK, walk.kind)
        assertEquals(RouteTransferKind.METRO_EXIT, walk.transferKind)
        assertEquals("Выход 7 · пешком 310 м", walk.instruction)
        assertTrue(walk.from.id.startsWith("metro-exit:"))
    }

    @Test
    fun explicitUndergroundTransferKeepsItsType() {
        val undergroundA = RoutePlace("u1", "Подземный переход", GeoPoint(55.2, 37.2))
        val undergroundB = RoutePlace("u2", "Тоннель к платформе", GeoPoint(55.2002, 37.2002))
        val route = candidate(
            RouteLeg(TransportMode.METRO, station, undergroundA, 0, 500, lineId = "6"),
            RouteLeg(TransportMode.WALK, undergroundA, undergroundB, 500, 620, walkMeters = 140),
            RouteLeg(TransportMode.METRO, undergroundB, b, 620, 1_100, lineId = "7")
        )

        val transfer = RoutePresentation.steps(route)[1]

        assertEquals(RouteTransferKind.UNDERGROUND, transfer.transferKind)
        assertEquals("Подземный переход 140 м", transfer.instruction)
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

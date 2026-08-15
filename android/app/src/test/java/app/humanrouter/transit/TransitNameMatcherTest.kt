package app.humanrouter.transit

import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitNameMatcherTest {
    @Test
    fun exactRailStationWinsOverSurfaceStopWithTheSameName() {
        val matches = TransitNameMatcher.search(
            query = "Угрешская",
            places = listOf(
                place("bus", "Угрешская", TransportMode.BUS, TransitPlaceSource.SURFACE),
                place("rail", "станция «Угрешская»", TransportMode.MCC, TransitPlaceSource.RAIL_GRAPH)
            ),
            focus = null,
            limit = 6
        )

        assertEquals(listOf("rail"), matches.map { it.id })
    }

    @Test
    fun modeWordsAndQuotesDoNotPreventAStationMatch() {
        val matches = TransitNameMatcher.search(
            query = "метро Текстильщики",
            places = listOf(
                place("metro", "Метро «Текстильщики»", TransportMode.METRO, TransitPlaceSource.RAIL_GRAPH)
            ),
            focus = null,
            limit = 6
        )

        assertEquals("metro", matches.single().id)
    }

    @Test
    fun prefixSearchReturnsTheNearestDuplicateOnlyOnce() {
        val matches = TransitNameMatcher.search(
            query = "Кузьм",
            places = listOf(
                place("far", "Кузьминки", TransportMode.METRO, TransitPlaceSource.RAIL_GRAPH, 55.9),
                place("near", "Кузьминки", TransportMode.METRO, TransitPlaceSource.RAIL_GRAPH, 55.7)
            ),
            focus = GeoPoint(55.7, 37.7),
            limit = 6
        )

        assertEquals(listOf("near"), matches.map { it.id })
    }

    private fun place(
        id: String,
        name: String,
        mode: TransportMode,
        source: TransitPlaceSource,
        lat: Double = 55.7
    ) = IndexedTransitPlace(id, name, GeoPoint(lat, 37.7), setOf(mode), source)
}

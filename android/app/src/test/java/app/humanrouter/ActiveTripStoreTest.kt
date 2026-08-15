package app.humanrouter

import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.RoutePlace
import app.humanrouter.routing.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveTripStoreTest {
    @Test
    fun snapshotRoundTripPreservesTheCompleteRoute() {
        val a = RoutePlace("a", "A", GeoPoint(55.75, 37.61))
        val b = RoutePlace("b", "B", GeoPoint(55.76, 37.62))
        val c = RoutePlace("c", "C", GeoPoint(55.77, 37.63))
        val snapshot = ActiveTripSnapshot(
            route = RouteCandidate(
                id = "active-route",
                requestedDepartureEpochSec = 10_000,
                legs = listOf(
                    RouteLeg(
                        mode = TransportMode.WALK,
                        from = a,
                        to = b,
                        departureEpochSec = 10_000,
                        arrivalEpochSec = 10_300,
                        walkMeters = 360,
                        realtimeConfidence = 0.95
                    ),
                    RouteLeg(
                        mode = TransportMode.MCD,
                        from = b,
                        to = c,
                        departureEpochSec = 10_500,
                        arrivalEpochSec = 11_100,
                        lineId = "mtppk:test",
                        lineName = "D3 · 7201",
                        waitSeconds = 200,
                        uncertaintySeconds = 180,
                        realtimeConfidence = 0.82,
                        stopCount = 4
                    )
                )
            ),
            originTitle = "Дом",
            originSubtitle = "Москва",
            destinationTitle = "Работа",
            destinationSubtitle = "Зеленоград"
        )

        assertEquals(snapshot, ActiveTripStore.decode(ActiveTripStore.encode(snapshot)))
    }
}

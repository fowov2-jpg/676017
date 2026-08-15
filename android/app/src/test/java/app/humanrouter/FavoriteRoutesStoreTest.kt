package app.humanrouter

import app.humanrouter.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteRoutesStoreTest {
    @Test
    fun codecRoundTripsAndRejectsBrokenJson() {
        val route = FavoriteRoute(
            id = "route-1",
            originTitle = "Дом",
            originSubtitle = "Москва",
            origin = GeoPoint(55.75, 37.61),
            destinationTitle = "Работа",
            destinationSubtitle = "Москва",
            destination = GeoPoint(55.76, 37.62)
        )
        assertEquals(listOf(route), FavoriteRoutesStore.decode(FavoriteRoutesStore.encode(listOf(route))))
        assertTrue(FavoriteRoutesStore.decode("not-json").isEmpty())
    }
}

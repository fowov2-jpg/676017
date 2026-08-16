package app.humanrouter.search

import app.humanrouter.routing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastAddressResolverTest {
    @Test
    fun abbreviatedMoscowHouseGetsExplicitStreetAndHouseVariant() {
        val variants = FastAddressResolver.queryVariantsForTest("Шумилова 13")

        assertTrue(variants.any { it.contains("Москва") && it.contains("улица Шумилова") && it.contains("дом 13") })
        assertTrue(variants.contains("Шумилова 13"))
    }

    @Test
    fun numberedStreetUsesLastNumberAsHouse() {
        val variants = FastAddressResolver.queryVariantsForTest("1-я Тверская-Ямская 13")

        assertTrue(variants.any { it.contains("1-я Тверская-Ямская") && it.contains("дом 13") })
        assertFalse(variants.any { it.contains("дом 1,") })
    }

    @Test
    fun houseCorporaRankAboveGenericStreetCentroid() {
        val centroid = SearchPlace(
            title = "улица Шумилова",
            subtitle = "Кузьминки, Москва",
            point = GeoPoint(55.70, 37.75)
        )
        val corpus1 = SearchPlace(
            title = "13к1",
            subtitle = "улица Шумилова 13к1, Кузьминки, Москва",
            point = GeoPoint(55.704, 37.752)
        )
        val corpus2 = SearchPlace(
            title = "13к2",
            subtitle = "улица Шумилова 13 корпус 2, Кузьминки, Москва",
            point = GeoPoint(55.705, 37.753)
        )

        val ranked = FastAddressResolver.rankForTest("Шумилова 13", listOf(centroid, corpus1, corpus2))

        assertTrue(ranked.take(2).all { it !== centroid })
        assertTrue(ranked.first() === corpus1 || ranked.first() === corpus2)
    }
}

package app.humanrouter

import app.humanrouter.routing.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitVisualCatalogTest {
    @Test
    fun metroLinesUseDistinctMoscowPalette() {
        assertEquals(1, TransitVisualCatalog.metroLineNumber("Сокольническая линия"))
        assertEquals(6, TransitVisualCatalog.metroLineNumber("M6"))
        assertEquals(7, TransitVisualCatalog.metroLineNumber("м7"))
        assertEquals(11, TransitVisualCatalog.metroLineNumber("Большая кольцевая линия"))
        assertEquals(16, TransitVisualCatalog.metroLineNumber("Троицкая линия"))

        val red = TransitVisualCatalog.colorHex(TransportMode.METRO, "1")
        val purple = TransitVisualCatalog.colorHex(TransportMode.METRO, "7")
        val turquoise = TransitVisualCatalog.colorHex(TransportMode.METRO, "11")
        assertTrue(red != purple)
        assertTrue(purple != turquoise)
        assertTrue(red.startsWith("#") && red.length == 7)
    }

    @Test
    fun mcdBadgesPreserveDNumberAndColor() {
        assertEquals(3, TransitVisualCatalog.mcdLineNumber("D3 · 7201"))
        assertEquals("D3", TransitVisualCatalog.badgeFor(TransportMode.MCD, "D3 · 7201"))
        assertEquals("D5", TransitVisualCatalog.badgeFor(TransportMode.MCD, "D5"))
        assertTrue(
            TransitVisualCatalog.colorHex(TransportMode.MCD, "D3") !=
                TransitVisualCatalog.colorHex(TransportMode.MCD, "D4")
        )
    }

    @Test
    fun surfaceAndRailModesHaveReferenceBadgeSemantics() {
        assertEquals("м3", TransitVisualCatalog.badgeFor(TransportMode.BUS, "м3"))
        assertEquals("39", TransitVisualCatalog.badgeFor(TransportMode.TRAM, "39"))
        assertEquals("14", TransitVisualCatalog.badgeFor(TransportMode.MCC, "МЦК"))
        assertEquals("6501", TransitVisualCatalog.badgeFor(TransportMode.TRAIN, "6501"))
        assertEquals("Аэроэкспресс", TransitVisualCatalog.badgeFor(TransportMode.TRAIN, "Аэроэкспресс"))
    }
}

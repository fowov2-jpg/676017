package app.humanrouter

import android.content.Intent
import app.humanrouter.routing.TransportMode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransitVisualSystemSmokeTest {
    @Test
    fun routeScreenShowsReferenceTransportStrip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("qa_screen", "routes")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withContentDescription("Схема транспорта маршрута"))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Транспорт: автобус; м2"))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Схема транспорта маршрута"))
                .perform(swipeLeft())
            onView(withContentDescription("Транспорт: метро; 6"))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun activeTripShowsReferenceTransportStrip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("qa_screen", "trip")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withContentDescription("Схема транспорта маршрута"))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun referencePaletteAndBadgesAreStable() {
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

        assertEquals(3, TransitVisualCatalog.mcdLineNumber("D3 · 7201"))
        assertEquals("D3", TransitVisualCatalog.badgeFor(TransportMode.MCD, "D3 · 7201"))
        assertEquals("D5", TransitVisualCatalog.badgeFor(TransportMode.MCD, "D5"))
        assertTrue(
            TransitVisualCatalog.colorHex(TransportMode.MCD, "D3") !=
                TransitVisualCatalog.colorHex(TransportMode.MCD, "D4")
        )

        assertEquals("м3", TransitVisualCatalog.badgeFor(TransportMode.BUS, "м3"))
        assertEquals("39", TransitVisualCatalog.badgeFor(TransportMode.TRAM, "39"))
        assertEquals("14", TransitVisualCatalog.badgeFor(TransportMode.MCC, "МЦК"))
        assertEquals("6501", TransitVisualCatalog.badgeFor(TransportMode.TRAIN, "6501"))
        assertEquals("Аэроэкспресс", TransitVisualCatalog.badgeFor(TransportMode.TRAIN, "Аэроэкспресс"))
    }
}

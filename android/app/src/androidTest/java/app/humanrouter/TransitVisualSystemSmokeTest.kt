package app.humanrouter

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            onView(withContentDescription("Автобус м2"))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Метро 6"))
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
}

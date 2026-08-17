package app.humanrouter

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class TransitStopInteractionInstrumentationTest {
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun clickedStopShowsRealDirectionAndCanBecomeOrigin() {
        launchNearby()
        openStop("qa:bus")

        onView(stopCardTitle("Театральная площадь")).check(matches(isDisplayed()))
        onView(withSubstring("→ Лубянка · в центр")).check(matches(isDisplayed()))
        onView(withSubstring("→ Фили · из центра")).check(matches(isDisplayed()))
        capture("stop-bus-directions")
        onView(withText("Отсюда")).check(matches(isDisplayed())).perform(click())

        onView(withId(R.id.fromField)).check(matches(withSubstring("Театральная площадь")))
        onView(withId(R.id.toField)).check(matches(isDisplayed()))
    }

    @Test
    fun clickedStationCanBecomeDestination() {
        launchNearby()
        openStop("qa:metro")

        onView(stopCardTitle("Охотный Ряд")).check(matches(isDisplayed()))
        onView(withSubstring("→ Бульвар Рокоссовского")).check(matches(isDisplayed()))
        capture("stop-metro-directions")
        onView(withText("Сюда")).check(matches(isDisplayed())).perform(click())

        onView(withId(R.id.toField)).check(matches(withSubstring("Охотный Ряд")))
    }

    private fun stopCardTitle(title: String) = allOf(
        withText(title),
        withTagValue(`is`("vh_transit_stop_title"))
    )

    private fun launchNearby() {
        scenario = ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                putExtra("qa_screen", "nearby")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        SystemClock.sleep(1_250L)
        scenario!!.onActivity { activity ->
            assertTrue(
                "typed transport marker layer did not receive QA places",
                TransitStopMapControllerV3.markerCountForQa(activity) >= 4
            )
        }
    }

    private fun openStop(id: String) {
        var opened = false
        scenario!!.onActivity { activity ->
            opened = TransitStopMapControllerV3.openForQa(activity, id)
        }
        assertTrue("QA stop $id was not available", opened)
        SystemClock.sleep(350L)
    }

    private fun capture(name: String) {
        val application = ApplicationProvider.getApplicationContext<VremyaHodomApp>()
        val directory = File(checkNotNull(application.getExternalFilesDir(null)), "stop-sheet")
        assertTrue(directory.exists() || directory.mkdirs())
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = File(directory, "$name.png")
        FileOutputStream(output).use { stream ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        screenshot.recycle()
        assertTrue("stop sheet screenshot missing: $output", output.isFile && output.length() > 1_000L)
    }
}

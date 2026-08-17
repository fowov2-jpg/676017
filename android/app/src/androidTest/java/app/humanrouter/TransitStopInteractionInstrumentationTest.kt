package app.humanrouter

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
        openStop(
            id = "qa:bus",
            title = "Театральная площадь",
            directionNeedles = listOf("→ Лубянка · в центр", "→ Фили · из центра")
        )

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
        openStop(
            id = "qa:metro",
            title = "Охотный Ряд",
            directionNeedles = listOf("→ Бульвар Рокоссовского")
        )

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
        waitForTypedMarkers()
    }

    /**
     * MapLibre style attachment is asynchronous and can take longer after an Activity recreation on
     * newer Android images. Wait for the actual product condition instead of assuming that a fixed
     * sleep means the marker source is ready. The assertion is unchanged: four QA transport places
     * must reach the typed marker controller, otherwise this still fails hard at the deadline.
     */
    private fun waitForTypedMarkers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + MARKER_READY_TIMEOUT_MS
        var markerCount = 0
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario!!.onActivity { activity ->
                markerCount = TransitStopMapControllerV3.markerCountForQa(activity)
            }
            if (markerCount >= 4) return
            SystemClock.sleep(MARKER_POLL_MS)
        }
        assertTrue(
            "typed transport marker layer did not receive QA places before timeout; count=$markerCount",
            markerCount >= 4
        )
    }

    /**
     * Opening the sheet is synchronous, but its direction rows are loaded on the controller's
     * direction worker. Wait for the actual title + direction content instead of sleeping for an
     * arbitrary amount of time. This also catches lifecycle regressions where a late MapLibre rebind
     * removes a sheet after it was successfully opened.
     */
    private fun openStop(id: String, title: String, directionNeedles: List<String>) {
        var opened = false
        scenario!!.onActivity { activity ->
            opened = TransitStopMapControllerV3.openForQa(activity, id)
        }
        assertTrue("QA stop $id was not available", opened)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + SHEET_READY_TIMEOUT_MS
        var lastTitle: String? = null
        var lastDirectionText = ""
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario!!.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                lastTitle = root.findViewWithTag<TextView>("vh_transit_stop_title")?.text?.toString()
                val directionRoot = root.findViewWithTag<View>("vh_transit_directions")
                lastDirectionText = collectText(directionRoot)
            }
            if (lastTitle == title && directionNeedles.all(lastDirectionText::contains)) return
            SystemClock.sleep(SHEET_POLL_MS)
        }
        assertTrue(
            "stop sheet $id did not remain ready before timeout; title=$lastTitle directions=$lastDirectionText",
            lastTitle == title && directionNeedles.all(lastDirectionText::contains)
        )
    }

    private fun collectText(view: View?): String {
        if (view == null) return ""
        val items = ArrayList<String>()
        fun walk(node: View) {
            if (node is TextView) node.text?.toString()?.takeIf(String::isNotBlank)?.let(items::add)
            if (node is ViewGroup) for (index in 0 until node.childCount) walk(node.getChildAt(index))
        }
        walk(view)
        return items.joinToString("\n")
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

    private companion object {
        const val MARKER_READY_TIMEOUT_MS = 8_000L
        const val MARKER_POLL_MS = 100L
        const val SHEET_READY_TIMEOUT_MS = 5_000L
        const val SHEET_POLL_MS = 75L
    }
}

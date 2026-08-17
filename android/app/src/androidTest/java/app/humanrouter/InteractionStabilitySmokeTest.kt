package app.humanrouter

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InteractionStabilitySmokeTest {

    @Test
    fun repeatedSearchSettingsAndNavigationReturnToIdle() {
        launch("home").use { scenario ->
            repeat(4) {
                onView(withId(R.id.compactSearchButton)).perform(click())
                onView(withId(R.id.routeButton)).check(matches(isDisplayed()))
                onView(withId(R.id.closeSearchButton)).perform(click())

                onView(withId(R.id.settingsButton)).perform(click())
                onView(withId(R.id.settingsPanel)).check(matches(isDisplayed()))
                onView(withId(R.id.closeSettingsButton)).perform(click())

                onView(withId(R.id.routesNavButton)).perform(click())
                onView(withId(R.id.mapNavButton)).perform(click())
            }

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.searchPanel).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.bottomNav).visibility)
                assertTrue("unexpected overlay duplication after repeated navigation", root.childCount < 24)
                assertTrue(countTag(root, "reference_active_trip_top") <= 1)
                assertTrue(countTag(root, "reference_active_trip_mini") <= 1)
                assertTrue(countTag(root, "reference_route_endpoints") <= 1)
            }
        }
    }

    @Test
    fun activeTripChromeDoesNotDuplicateAfterRecreation() {
        launch("trip").use { scenario ->
            repeat(3) {
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<FrameLayout>(R.id.root)
                    assertEquals(1, countTag(root, "reference_active_trip_top"))
                    assertEquals(1, countTag(root, "reference_active_trip_mini"))
                }
            }
        }
    }

    private fun countTag(root: View, tag: String): Int {
        var count = 0
        fun walk(view: View) {
            if (view.tag?.toString() == tag) count += 1
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return count
    }

    private fun launch(screen: String): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("qa_screen", screen)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

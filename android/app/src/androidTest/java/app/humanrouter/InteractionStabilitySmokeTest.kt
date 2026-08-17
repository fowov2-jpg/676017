package app.humanrouter

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InteractionStabilitySmokeTest {

    @Test
    fun repeatedSearchSettingsAndNavigationReturnToIdle() {
        // Use the deterministic QA location state here. This test is about repeated app-owned
        // transitions, not the Android runtime-permission window (covered by MainActivitySmokeTest).
        // Starting from location_allowed prevents a system permission surface from stealing focus
        // while the emulator is still settling after pm clear / Activity launch.
        launch("location_allowed").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                // location_allowed intentionally starts with the expanded destination search.
                // Collapse it directly once so every loop begins from the same map-first state.
                activity.findViewById<View>(R.id.closeSearchButton).performClick()
            }
            waitForWindowFocus(scenario)

            repeat(4) {
                waitForWindowFocus(scenario)
                onView(withId(R.id.compactSearchButton)).perform(click())
                onView(withId(R.id.routeButton)).check(matches(isDisplayed()))
                onView(withId(R.id.closeSearchButton)).perform(click())

                onView(withId(R.id.settingsButton)).perform(click())
                onView(withId(R.id.settingsPanel)).check(matches(isDisplayed()))
                onView(withId(R.id.closeSettingsButton)).perform(click())
                // The settings scrim is removed in the 150 ms panel animation end action. Wait for
                // that real transition before tapping bottom navigation so the fading scrim cannot
                // intercept the Routes click on slower/emulated renderers.
                onView(isRoot()).perform(waitForUi(220L))

                // With no built route, the Routes tab intentionally opens the expanded search and
                // hides bottom navigation. Exercise that real state transition, close it, then
                // return to Map through the visible navigation instead of clicking a hidden view.
                onView(withId(R.id.routesNavButton)).perform(click())
                onView(withId(R.id.routeButton)).check(matches(isDisplayed()))
                onView(withId(R.id.closeSearchButton)).perform(click())
                onView(withId(R.id.mapNavButton)).check(matches(isDisplayed())).perform(click())
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

    private fun waitForWindowFocus(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 15_000L
        var ready = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                ready = decor.hasWindowFocus() && !decor.isLayoutRequested
            }
            if (ready) return
            SystemClock.sleep(75L)
        }
        assertTrue("MainActivity window did not regain focus before stability interaction", ready)
    }

    private fun waitForUi(milliseconds: Long): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription(): String = "wait $milliseconds ms for UI transition"
        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadForAtLeast(milliseconds)
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

package app.humanrouter

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

        onView(withText("Театральная площадь")).check(matches(isDisplayed()))
        onView(withSubstring("→ Лубянка · в центр")).check(matches(isDisplayed()))
        onView(withSubstring("→ Фили · из центра")).check(matches(isDisplayed()))
        onView(withText("Отсюда")).check(matches(isDisplayed())).perform(click())

        onView(withId(R.id.fromField)).check(matches(withSubstring("Театральная площадь")))
        onView(withId(R.id.toField)).check(matches(isDisplayed()))
    }

    @Test
    fun clickedStationCanBecomeDestination() {
        launchNearby()
        openStop("qa:metro")

        onView(withText("Охотный Ряд")).check(matches(isDisplayed()))
        onView(withSubstring("→ Бульвар Рокоссовского")).check(matches(isDisplayed()))
        onView(withText("Сюда")).check(matches(isDisplayed())).perform(click())

        onView(withId(R.id.toField)).check(matches(withSubstring("Охотный Ряд")))
    }

    private fun launchNearby() {
        scenario = ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                putExtra("qa_screen", "nearby")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        SystemClock.sleep(1_250L)
    }

    private fun openStop(id: String) {
        var opened = false
        scenario!!.onActivity { activity ->
            opened = TransitStopMapControllerV2.openForQa(activity, id)
        }
        assertTrue("QA stop $id was not available", opened)
        SystemClock.sleep(350L)
    }
}

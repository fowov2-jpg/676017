package app.humanrouter

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedUiSmokeTest {
    @Test
    fun routeChoiceKeepsGlobalJourneyStripOutOfTheViewport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("qa_screen", "routes")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            // The unified journey strip still exists for active-trip mode, but route selection is
            // summary-first: transport chains live inside each option card instead of consuming a
            // second global row above the cards.
            onView(withContentDescription("Этапы маршрута по видам транспорта"))
                .check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun settingsDescribeScheduleVsRealtimeHonestly() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("qa_screen", "home")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withId(R.id.settingsButton)).perform(click())
            onView(withText("Показывать линии маршрута")).check(matches(isDisplayed()))
            onView(withText(containsString("Live-позиции транспорта не подключены")))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun continuousJourneySceneIsInstalledAsOneView() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("qa_screen", "home")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(
                allOf(
                    withContentDescription("Анимация поездки: человек, остановка, автобус, метро и поезд"),
                    isDescendantOfA(withId(R.id.journeyRow))
                )
            ).check(matches(withContentDescription("Анимация поездки: человек, остановка, автобус, метро и поезд")))
        }
    }
}

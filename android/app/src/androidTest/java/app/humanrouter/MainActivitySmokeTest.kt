package app.humanrouter

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun resetPreferences() {
        ApplicationProvider.getApplicationContext<VremyaHodomApp>()
            .getSharedPreferences(AppPreferences.NAME, 0)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun closeActivity() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun homeNavigationSettingsAndRecreationRemainUsable() {
        launch("home")

        onView(withId(R.id.compactSearchButton)).check(matches(isDisplayed()))
        onView(withId(R.id.quickActions)).check(matches(isDisplayed()))
        onView(withId(R.id.compactSearchButton)).perform(click())
        onView(withSubstring("Использовать центр карты как место назначения")).perform(click())
        onView(withId(R.id.toField)).check(matches(withSubstring("Точка на карте")))
        onView(withId(R.id.closeSearchButton)).perform(click())
        onView(withId(R.id.settingsButton)).perform(click())
        onView(withText(R.string.settings)).check(matches(isDisplayed()))
        onView(withId(R.id.darkThemeSwitch)).check(matches(isDisplayed()))
        onView(withId(R.id.closeSettingsButton)).perform(click())
        onView(isRoot()).perform(waitForUi(200L))

        onView(withId(R.id.transportNavButton)).perform(click())
        onView(withText(R.string.nearby_title)).check(matches(isDisplayed()))
        onView(withId(R.id.favoritesNavButton)).perform(click())
        onView(withText(R.string.favorites_empty)).check(matches(isDisplayed()))
        onView(withId(R.id.mapNavButton)).perform(click())

        scenario!!.recreate()
        onView(withId(R.id.mapView)).check(matches(isDisplayed()))
        onView(withId(R.id.bottomNav)).check(matches(isDisplayed()))
    }

    @Test
    fun locationPermissionStatesKeepManualOriginAvailable() {
        launch("permission_denied")
        onView(withId(R.id.locationActionMessage))
            .check(matches(withSubstring("Геопозиция не разрешена")))
        onView(withId(R.id.locationSecondaryAction)).perform(click())
        onView(withId(R.id.fromField)).check(matches(withSubstring("Точка на карте")))

        relaunch("permission_permanently_denied")
        onView(withId(R.id.locationActionMessage))
            .check(matches(withSubstring("Доступ к геопозиции отключён")))
        onView(withId(R.id.locationPrimaryAction)).check(matches(withText(R.string.permission_settings)))
        onView(withId(R.id.locationSecondaryAction)).check(matches(withText(R.string.permission_choose_map)))

        relaunch("location_disabled")
        onView(withId(R.id.locationActionMessage))
            .check(matches(withSubstring("Геолокация на телефоне выключена")))
        onView(withId(R.id.locationSecondaryAction)).check(matches(withText(R.string.permission_choose_map)))

        relaunch("location_allowed")
        onView(withId(R.id.fromField)).check(matches(withSubstring("Моё местоположение")))
        onView(withId(R.id.toField)).check(matches(isDisplayed()))
    }

    @Test
    fun routeOptionsFiltersFavoritesAndTripFlowAreInteractive() {
        launch("routes")
        onView(withText("Варианты маршрута")).check(matches(isDisplayed()))
        onView(withText("Наземный транспорт")).perform(scrollTo(), click())
        onView(withText("☆ Сохранить маршрут")).perform(scrollTo(), click())
        onView(withText("✓ Маршрут сохранён")).perform(scrollTo()).check(matches(isDisplayed()))

        relaunch("trip")
        onView(withText("В пути")).check(matches(isDisplayed()))
        onView(withId(R.id.favoritesNavButton)).perform(click())
        onView(withId(R.id.routesNavButton)).perform(click())
        onView(withText("В пути")).check(matches(isDisplayed()))
        onView(withText("Завершить поездку")).perform(scrollTo(), click())
        onView(withText("Варианты маршрута")).check(matches(isDisplayed()))
    }

    @Test
    fun darkThemeAndRotationPreserveTheMainScreen() {
        launch("home", dark = true)
        scenario!!.onActivity { activity ->
            assertEquals(
                Configuration.UI_MODE_NIGHT_YES,
                activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            )
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onView(withId(R.id.mapView)).check(matches(isDisplayed()))
        scenario!!.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onView(withId(R.id.compactSearchButton)).check(matches(isDisplayed()))
        scenario!!.recreate()
        onView(withId(R.id.bottomNav)).check(matches(isDisplayed()))
    }

    private fun launch(screen: String, dark: Boolean = false) {
        scenario = ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                putExtra("qa_screen", screen)
                putExtra("qa_dark", dark)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun relaunch(screen: String, dark: Boolean = false) {
        scenario?.close()
        scenario = null
        launch(screen, dark)
    }

    private fun waitForUi(milliseconds: Long): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()

        override fun getDescription(): String = "wait $milliseconds ms for UI animation"

        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadForAtLeast(milliseconds)
        }
    }
}

package app.humanrouter

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

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
        closeSoftKeyboard()
        onView(isRoot()).perform(waitForUi(250L))
        onView(withId(R.id.settingsButton)).perform(click())
        onView(withText(R.string.settings)).check(matches(isDisplayed()))
        onView(withId(R.id.darkThemeSwitch)).check(matches(isDisplayed()))
        onView(withId(R.id.closeSettingsButton)).perform(click())
        onView(isRoot()).perform(waitForUi(200L))

        captureNavigationDiagnostics()
        onView(withId(R.id.transportNavButton)).perform(click())
        onView(withId(R.id.nearbyTitle)).check(matches(isDisplayed()))
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
        waitForOrientation(Configuration.ORIENTATION_LANDSCAPE)
        onView(withId(R.id.mapView)).check(matches(isDisplayed()))
        scenario!!.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        waitForOrientation(Configuration.ORIENTATION_PORTRAIT)
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

    private fun waitForOrientation(expectedOrientation: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var actualOrientation = Configuration.ORIENTATION_UNDEFINED
        var windowReady = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            runCatching {
                scenario!!.onActivity { activity ->
                    actualOrientation = activity.resources.configuration.orientation
                    val decor = activity.window.decorView
                    windowReady = decor.hasWindowFocus() && !decor.isLayoutRequested
                }
            }
            if (actualOrientation == expectedOrientation && windowReady) return
            SystemClock.sleep(50L)
        }
        check(actualOrientation == expectedOrientation && windowReady) {
            "Orientation did not settle: expected=$expectedOrientation, actual=$actualOrientation, " +
                "windowReady=$windowReady"
        }
    }

    private fun captureNavigationDiagnostics() {
        val application = ApplicationProvider.getApplicationContext<VremyaHodomApp>()
        val directory = checkNotNull(application.getExternalFilesDir(null))
        scenario!!.onActivity { activity ->
            val root = activity.findViewById<View>(R.id.root)
            val bottomNav = activity.findViewById<View>(R.id.bottomNav)
            val target = activity.findViewById<View>(R.id.transportNavButton)
            val rootInsets = ViewCompat.getRootWindowInsets(root)
            val currentBars = rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            val stableBars = rootInsets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val imeInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())

            fun describe(label: String, view: View): String {
                val rect = Rect()
                val hasRect = view.getGlobalVisibleRect(rect)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                return "$label: size=${view.width}x${view.height}, location=${location[0]},${location[1]}, " +
                    "global=$hasRect/$rect, visibility=${view.visibility}, windowVisibility=${view.windowVisibility}, " +
                    "shown=${view.isShown}, attached=${ViewCompat.isAttachedToWindow(view)}, " +
                    "layoutRequested=${view.isLayoutRequested}"
            }

            val ancestors = buildString {
                var view: View? = target
                while (view != null) {
                    append(view.javaClass.simpleName)
                        .append("(visibility=").append(view.visibility)
                        .append(", shown=").append(view.isShown)
                        .append(")")
                    view = view.parent as? View
                    if (view != null) append(" <- ")
                }
            }
            File(directory, "navigation-after-ime.txt").writeText(
                buildString {
                    appendLine(describe("root", root))
                    appendLine(describe("bottomNav", bottomNav))
                    appendLine(describe("transportNavButton", target))
                    appendLine("ancestors=$ancestors")
                    appendLine("currentSystemBars=$currentBars")
                    appendLine("stableSystemBars=$stableBars")
                    appendLine("imeInsets=$imeInsets")
                    appendLine("imeVisible=${rootInsets?.isVisible(WindowInsetsCompat.Type.ime())}")
                }
            )
        }

        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(directory, "navigation-after-ime.png")).use { stream ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        screenshot.recycle()
    }

}

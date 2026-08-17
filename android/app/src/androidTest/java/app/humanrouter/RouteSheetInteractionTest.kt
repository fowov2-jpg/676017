package app.humanrouter

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteSheetInteractionTest {
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launchRoutes() {
        ApplicationProvider.getApplicationContext<VremyaHodomApp>()
            .getSharedPreferences(AppPreferences.NAME, 0)
            .edit()
            .clear()
            .commit()
        scenario = ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                putExtra("qa_screen", "routes")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        onView(isRoot()).perform(waitForUi(250L))
    }

    @After
    fun close() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun routeSheetStartsCompactAndRespondsToUserGestures() {
        var initialHeight = 0
        var rootHeight = 0
        scenario!!.onActivity { activity ->
            val root = activity.findViewById<View>(R.id.root)
            val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
            rootHeight = root.height
            initialHeight = sheet.height
            assertTrue("route sheet does not start compact", initialHeight < rootHeight * 0.45f)
            assertTrue("route sheet is too small to be useful", initialHeight > rootHeight * 0.28f)
        }

        onView(withId(R.id.routeResultsContainer)).perform(dragHandle(expand = true))
        var expandedHeight = 0
        scenario!!.onActivity { activity ->
            expandedHeight = activity.findViewById<View>(R.id.routeResultsContainer).height
            assertTrue("drag up did not expand route sheet", expandedHeight > initialHeight + rootHeight * 0.08f)
        }

        onView(withId(R.id.routeResultsContainer)).perform(dragHandle(expand = false))
        scenario!!.onActivity { activity ->
            val collapsedHeight = activity.findViewById<View>(R.id.routeResultsContainer).height
            assertTrue("drag down did not reduce route sheet", collapsedHeight < expandedHeight)
            assertTrue("collapsed route sheet hides too much map", collapsedHeight < rootHeight * 0.45f)
        }
    }

    private fun dragHandle(expand: Boolean): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription(): String = if (expand) "drag route sheet handle up" else "drag route sheet handle down"

        override fun perform(uiController: UiController, view: View) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val density = view.resources.displayMetrics.density
            val x = location[0] + view.width / 2f
            val startY = location[1] + minOf(view.height * 0.08f, 28f * density)
            val distance = view.rootView.height * 0.28f * if (expand) -1f else 1f
            val endY = startY + distance
            val downTime = SystemClock.uptimeMillis()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

            fun inject(action: Int, y: Float, eventTime: Long) {
                MotionEvent.obtain(downTime, eventTime, action, x, y, 0).also { event ->
                    automation.injectInputEvent(event, true)
                    event.recycle()
                }
            }

            inject(MotionEvent.ACTION_DOWN, startY, downTime)
            repeat(8) { index ->
                val fraction = (index + 1) / 8f
                inject(
                    MotionEvent.ACTION_MOVE,
                    startY + distance * fraction,
                    downTime + (index + 1) * 18L
                )
            }
            inject(MotionEvent.ACTION_UP, endY, downTime + 180L)
            uiController.loopMainThreadForAtLeast(260L)
        }
    }

    private fun waitForUi(milliseconds: Long): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription(): String = "wait $milliseconds ms for route sheet animation"
        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadForAtLeast(milliseconds)
        }
    }
}

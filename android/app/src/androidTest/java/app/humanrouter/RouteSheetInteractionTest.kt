package app.humanrouter

import android.content.Intent
import android.graphics.Rect
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
        var initialVisibleHeight = 0
        var rootVisibleHeight = 0
        scenario!!.onActivity { activity ->
            val root = activity.findViewById<View>(R.id.root)
            val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
            rootVisibleHeight = visibleHeight(root)
            initialVisibleHeight = visibleHeight(sheet)
            assertTrue("route sheet does not start compact", initialVisibleHeight < rootVisibleHeight * 0.45f)
            assertTrue("route sheet is too small to be useful", initialVisibleHeight > rootVisibleHeight * 0.28f)
        }

        onView(withId(R.id.routeResultsContainer)).perform(dragHandle(expand = true))
        var expandedVisibleHeight = 0
        scenario!!.onActivity { activity ->
            expandedVisibleHeight = visibleHeight(activity.findViewById(R.id.routeResultsContainer))
            assertTrue(
                "drag up did not expose more of the route sheet",
                expandedVisibleHeight > initialVisibleHeight + rootVisibleHeight * 0.08f
            )
        }

        onView(withId(R.id.routeResultsContainer)).perform(dragHandle(expand = false))
        scenario!!.onActivity { activity ->
            val collapsedVisibleHeight = visibleHeight(activity.findViewById(R.id.routeResultsContainer))
            assertTrue("drag down did not reduce visible route sheet", collapsedVisibleHeight < expandedVisibleHeight)
            assertTrue("collapsed route sheet hides too much map", collapsedVisibleHeight < rootVisibleHeight * 0.45f)
        }
    }

    private fun visibleHeight(view: View): Int {
        val rect = Rect()
        check(view.getGlobalVisibleRect(rect)) { "${view.javaClass.simpleName} has no visible rectangle" }
        return rect.height()
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

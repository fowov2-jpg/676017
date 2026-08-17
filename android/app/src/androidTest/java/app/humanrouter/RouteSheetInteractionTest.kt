package app.humanrouter

import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
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

        scenario!!.onActivity { activity -> dragRouteSheet(activity, expand = true) }
        onView(isRoot()).perform(waitForUi(260L))
        var expandedVisibleHeight = 0
        scenario!!.onActivity { activity ->
            expandedVisibleHeight = visibleHeight(activity.findViewById(R.id.routeResultsContainer))
            assertTrue(
                "drag up did not expose more of the route sheet",
                expandedVisibleHeight > initialVisibleHeight + rootVisibleHeight * 0.08f
            )
        }

        scenario!!.onActivity { activity -> dragRouteSheet(activity, expand = false) }
        onView(isRoot()).perform(waitForUi(260L))
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

    private fun dragRouteSheet(activity: MainActivity, expand: Boolean) {
        val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        val location = IntArray(2)
        sheet.getLocationOnScreen(location)
        val density = sheet.resources.displayMetrics.density
        val startX = location[0] + sheet.width / 2f
        val startY = location[1] + minOf(sheet.height * 0.08f, 28f * density)
        val endY = startY + sheet.rootView.height * 0.28f * if (expand) -1f else 1f
        val now = android.os.SystemClock.uptimeMillis()
        sheet.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, startY, 0))
        sheet.dispatchTouchEvent(MotionEvent.obtain(now, now + 16L, MotionEvent.ACTION_MOVE, startX, (startY + endY) / 2f, 0))
        sheet.dispatchTouchEvent(MotionEvent.obtain(now, now + 32L, MotionEvent.ACTION_MOVE, startX, endY, 0))
        sheet.dispatchTouchEvent(MotionEvent.obtain(now, now + 48L, MotionEvent.ACTION_UP, startX, endY, 0))
    }

    private fun waitForUi(milliseconds: Long): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription(): String = "wait $milliseconds ms for route sheet animation"
        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadForAtLeast(milliseconds)
        }
    }
}

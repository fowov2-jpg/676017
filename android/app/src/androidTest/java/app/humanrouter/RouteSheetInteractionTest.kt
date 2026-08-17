package app.humanrouter

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
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

        onView(withId(R.id.routeResultsContainer)).perform(swipeUp())
        onView(isRoot()).perform(waitForUi(260L))
        var expandedHeight = 0
        scenario!!.onActivity { activity ->
            expandedHeight = activity.findViewById<View>(R.id.routeResultsContainer).height
            assertTrue("swipe up did not expand route sheet", expandedHeight > initialHeight + rootHeight * 0.08f)
        }

        onView(withId(R.id.routeResultsContainer)).perform(swipeDown())
        onView(isRoot()).perform(waitForUi(260L))
        scenario!!.onActivity { activity ->
            val collapsedHeight = activity.findViewById<View>(R.id.routeResultsContainer).height
            assertTrue("swipe down did not reduce route sheet", collapsedHeight < expandedHeight)
            assertTrue("collapsed route sheet hides too much map", collapsedHeight < rootHeight * 0.45f)
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

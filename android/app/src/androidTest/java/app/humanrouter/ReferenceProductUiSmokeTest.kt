package app.humanrouter

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceProductUiSmokeTest {

    @Test
    fun homeUsesFloatingReferenceComposition() {
        launch("home").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                val search = activity.findViewById<View>(R.id.searchPanel)
                val nearby = activity.findViewById<View>(R.id.nearbyPanel)
                val nav = activity.findViewById<View>(R.id.bottomNav)
                val widthDp = root.width / activity.resources.displayMetrics.density
                assertTrue(search.width < root.width * if (widthDp >= 600f) 0.92f else 0.96f)
                assertTrue(search.height >= dp(activity, 54))
                assertTrue(nearby.height >= dp(activity, 120))
                assertTrue(nearby.height <= dp(activity, if (widthDp >= 600f) 290 else 250))
                assertTrue(nav.height >= dp(activity, 60))
                assertTrue(nav.height <= dp(activity, 82))
                assertNoOverlap(nearby, nav, "nearby sheet overlaps bottom navigation")
            }
        }
    }

    @Test
    fun settingsRemainASideSheetOverTheMap() {
        launch("settings").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                val settingsPanel = activity.findViewById<LinearLayout>(R.id.settingsPanel)
                val sheet = settingsPanel.parent as View
                val widthDp = root.width / activity.resources.displayMetrics.density
                val ratio = sheet.width.toFloat() / root.width.toFloat()
                if (widthDp >= 600f) {
                    assertTrue("tablet settings sheet too narrow", ratio >= 0.36f)
                    assertTrue("tablet settings sheet too wide", ratio <= 0.55f)
                } else {
                    assertTrue("phone settings sheet too narrow for labels", ratio >= 0.64f)
                    assertTrue("phone settings sheet hides too much map", ratio <= 0.86f)
                }
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mapView).visibility)
            }
        }
    }

    @Test
    fun routesExposeReferenceEndpointsAndThreeOptionViewport() {
        launch("routes").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.routeResultsContainer).findViewWithTag<View>("reference_route_endpoints"))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNav).visibility)
                val root = activity.findViewById<View>(R.id.root)
                val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
                val heightDp = root.height / activity.resources.displayMetrics.density
                val ratio = sheet.height.toFloat() / root.height.toFloat()
                val minRatio = if (heightDp < 700f) 0.58f else 0.50f
                assertTrue("route sheet is too short for alternatives", ratio >= minRatio)
                assertTrue("route sheet covers too much map", ratio <= 0.66f)
            }
        }
    }

    @Test
    fun activeTripOwnsTopAndBottomChromeWithoutOverlap() {
        launch("trip").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                val top = root.findViewWithTag<View>("reference_active_trip_top")
                val mini = root.findViewWithTag<View>("reference_active_trip_mini")
                val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
                assertNotNull(top)
                assertNotNull(mini)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.searchPanel).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNav).visibility)
                assertNoOverlap(top!!, sheet, "active top card overlaps trip sheet")
                assertNoOverlap(sheet, mini!!, "trip sheet overlaps bottom mini card")
            }
        }
    }

    private fun assertNoOverlap(first: View, second: View, message: String) {
        if (first.visibility != View.VISIBLE || second.visibility != View.VISIBLE) return
        val a = Rect()
        val b = Rect()
        assertTrue(first.getGlobalVisibleRect(a))
        assertTrue(second.getGlobalVisibleRect(b))
        assertTrue(message, !Rect.intersects(a, b))
    }

    private fun launch(screen: String): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("qa_screen", screen)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}

package app.humanrouter

import android.content.Intent
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
                assertTrue(search.width < root.width * 0.90f)
                assertTrue(search.height >= dp(activity, 58))
                assertTrue(nearby.height >= dp(activity, 200))
                assertTrue(nav.height >= dp(activity, 64))
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
                assertTrue(sheet.width < root.width * 0.48f)
                assertTrue(sheet.width > root.width * 0.36f)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mapView).visibility)
            }
        }
    }

    @Test
    fun routesExposeReferenceEndpointsAndFreeTheBottomEdge() {
        launch("routes").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.routeResultsContainer).findViewWithTag<View>("reference_route_endpoints"))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNav).visibility)
                val root = activity.findViewById<View>(R.id.root)
                val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
                assertTrue(sheet.height * 2 < root.height)
            }
        }
    }

    @Test
    fun activeTripOwnsTopAndBottomChrome() {
        launch("trip").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                assertNotNull(root.findViewWithTag<View>("reference_active_trip_top"))
                assertNotNull(root.findViewWithTag<View>("reference_active_trip_mini"))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.searchPanel).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNav).visibility)
            }
        }
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

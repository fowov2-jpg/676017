package app.humanrouter

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReferenceProductUiSmokeTest {

    @Test
    fun homeUsesFloatingReferenceComposition() {
        launch("home").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                val search = activity.findViewById<View>(R.id.searchPanel)
                val quick = activity.findViewById<View>(R.id.quickActions)
                val nearby = activity.findViewById<View>(R.id.nearbyPanel)
                val nav = activity.findViewById<View>(R.id.bottomNav)
                val location = activity.findViewById<View>(R.id.locationButton)
                val settings = activity.findViewById<View>(R.id.settingsButton)
                val widthDp = root.width / activity.resources.displayMetrics.density

                assertEquals(
                    "ВремяХодом",
                    activity.packageManager.getApplicationLabel(activity.applicationInfo).toString()
                )

                if (widthDp < 600f) {
                    val searchRatio = search.width.toFloat() / root.width.toFloat()
                    val quickRatio = quick.width.toFloat() / root.width.toFloat()
                    val nearbyRatio = nearby.width.toFloat() / root.width.toFloat()
                    assertTrue("phone search is still stretched edge-to-edge", searchRatio in 0.72f..0.90f)
                    assertTrue("phone quick actions are still stretched edge-to-edge", quickRatio in 0.60f..0.84f)
                    assertTrue("phone Nearby dock is too wide/narrow for reference", nearbyRatio in 0.72f..0.90f)
                    assertTrue("Nearby and bottom navigation must share one dock width", abs(nearby.width - nav.width) <= dp(activity, 2))
                    assertDockSeam(nearby, nav, activity)
                    assertTrue("map controls must stay in the upper half of the map", location.top < root.height * 0.48f)
                    assertTrue("map controls must stay in the upper half of the map", settings.top < root.height * 0.56f)
                    assertTrue(search.height in dp(activity, 50)..dp(activity, 66))
                } else {
                    assertTrue(search.width < root.width * 0.92f)
                    assertTrue(search.height >= dp(activity, 54))
                }

                assertTrue(nearby.height >= dp(activity, 110))
                assertTrue(nearby.height <= dp(activity, if (widthDp >= 600f) 290 else 230))
                assertTrue(nav.height >= dp(activity, 60))
                assertTrue(nav.height <= dp(activity, 82))
                assertNoOverlap(nearby, nav, "nearby sheet overlaps bottom navigation")
            }
        }
    }

    @Test
    fun populatedHomeStylesEveryScrollableNearbyRow() {
        launch("nearby").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                val widthDp = root.width / activity.resources.displayMetrics.density
                val list = activity.findViewById<LinearLayout>(R.id.nearbyList)
                assertEquals("QA populated fixture changed unexpectedly", 4, list.childCount)

                if (widthDp < 600f) {
                    val expectedModes = listOf("А", "М", "Т", "D/Э")
                    expectedModes.forEachIndexed { index, expectedMode ->
                        val row = list.getChildAt(index) as LinearLayout
                        val badge = row.getChildAt(0) as TextView
                        assertEquals("Nearby mode tag must preserve source semantics", expectedMode, badge.tag)
                        assertEquals("Reference owner must replace every mode label with an icon", "", badge.text.toString())
                        assertTrue(
                            "Reference owner must attach a transport glyph to every Nearby row",
                            badge.compoundDrawablesRelative.any { it != null }
                        )

                        val copy = row.getChildAt(1) as LinearLayout
                        val title = copy.getChildAt(0) as TextView
                        val textLayout = title.layout
                        assertNotNull("Nearby title must be laid out for visual validation", textLayout)
                        assertTrue(
                            "Nearby title must not be ellipsized on the reference phone: ${title.text}",
                            (0 until title.lineCount).all { line -> textLayout!!.getEllipsisCount(line) == 0 }
                        )
                    }
                }
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
                val routesPanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
                val density = activity.resources.displayMetrics.density
                val widthDp = root.width / density
                val heightDp = root.height / density
                val ratio = sheet.height.toFloat() / root.height.toFloat()
                val minRatio = if (heightDp < 700f) 0.58f else 0.50f
                assertTrue("route sheet is too short for alternatives", ratio >= minRatio)
                assertTrue("route sheet covers too much map", ratio <= 0.66f)

                if (widthDp < 600f) {
                    val routeCards = (0 until routesPanel.childCount)
                        .mapNotNull { routesPanel.getChildAt(it) as? LinearLayout }
                        .filter { it.isClickable }
                    assertTrue("reference route fixture must expose at least three alternatives", routeCards.size >= 3)

                    val thirdRoute = routeCards[2]
                    val visible = Rect()
                    assertTrue("third route alternative has no visible rectangle", thirdRoute.getGlobalVisibleRect(visible))
                    assertTrue(
                        "third route alternative must be fully visible without an initial drag",
                        visible.height() >= thirdRoute.height - dp(activity, 2)
                    )

                    routeCards.take(3).forEachIndexed { index, card ->
                        val chain = descendantTextViews(card)
                            .firstOrNull { it.text?.toString()?.contains('›') == true }
                        assertNotNull("route ${index + 1} must expose its transport chain", chain)
                        val layout = chain!!.layout
                        assertNotNull("route ${index + 1} chain must be laid out", layout)
                        assertTrue(
                            "route ${index + 1} chain must not be ellipsized",
                            (0 until chain.lineCount).all { line -> layout!!.getEllipsisCount(line) == 0 }
                        )
                        assertTrue(
                            "route ${index + 1} chain must show the complete semantic text",
                            chain.lineCount > 0 && layout!!.getLineEnd(chain.lineCount - 1) >= chain.text.length
                        )
                    }
                }
            }
        }
    }

    @Test
    fun activeTripUsesOnlyTopStatusAndBottomTripSheet() {
        launch("trip").use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<FrameLayout>(R.id.root)
                val top = root.findViewWithTag<View>("reference_active_trip_top")
                val mini = root.findViewWithTag<View>("reference_active_trip_mini")
                val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
                val location = activity.findViewById<View>(R.id.locationButton)
                val settings = activity.findViewById<View>(R.id.settingsButton)
                assertNotNull(top)
                assertNotNull(mini)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.searchPanel).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNav).visibility)
                assertEquals(View.VISIBLE, location.visibility)
                assertEquals(View.GONE, settings.visibility)
                assertEquals(
                    "legacy third active-trip bottom bar must never be visible",
                    View.GONE,
                    mini!!.visibility
                )
                assertNoOverlap(top!!, sheet, "active top card overlaps trip sheet")

                val rootRect = Rect()
                val sheetRect = Rect()
                val locationRect = Rect()
                assertTrue(root.getGlobalVisibleRect(rootRect))
                assertTrue(sheet.getGlobalVisibleRect(sheetRect))
                assertTrue(location.getGlobalVisibleRect(locationRect))
                assertTrue(
                    "active trip sheet still reserves space for a removed third bottom bar",
                    rootRect.bottom - sheetRect.bottom <= dp(activity, 64)
                )

                val widthDp = root.width / activity.resources.displayMetrics.density
                if (widthDp < 600f) {
                    assertTrue(
                        "active-trip location control must stay on the left map edge",
                        locationRect.centerX() < rootRect.left + rootRect.width() * 0.30f
                    )
                    assertTrue(
                        "active-trip location control must float above the journey sheet",
                        locationRect.bottom <= sheetRect.top - dp(activity, 6)
                    )
                    assertTrue(
                        "active-trip location control is detached too far from the journey sheet",
                        sheetRect.top - locationRect.bottom <= dp(activity, 36)
                    )
                }

                val topCopy = descendantTextViews(top).map { it.text?.toString().orEmpty() }.toList()
                assertTrue(
                    "active top chrome must describe the current walking leg before future transit: $topCopy",
                    topCopy.any { it.contains("Пеш", ignoreCase = true) }
                )
                assertTrue(
                    "future bus m2 must not be presented as the current top-stage badge: $topCopy",
                    topCopy.none { it.trim().equals("м2", ignoreCase = true) }
                )
            }
        }
    }

    private fun descendantTextViews(view: View): Sequence<TextView> = sequence {
        if (view is TextView) yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendantTextViews(view.getChildAt(index)))
        }
    }

    private fun assertDockSeam(nearby: View, nav: View, activity: MainActivity) {
        val nearbyRect = Rect()
        val navRect = Rect()
        assertTrue(nearby.getGlobalVisibleRect(nearbyRect))
        assertTrue(nav.getGlobalVisibleRect(navRect))
        val gap = navRect.top - nearbyRect.bottom
        assertTrue("Nearby and navigation must visually join into one dock, gap=$gap", abs(gap) <= dp(activity, 2))
        assertTrue("Nearby and navigation must share the same left edge", abs(nearbyRect.left - navRect.left) <= dp(activity, 2))
        assertTrue("Nearby and navigation must share the same right edge", abs(nearbyRect.right - navRect.right) <= dp(activity, 2))
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

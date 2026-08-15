package app.humanrouter

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class UiPolishSmokeTest {
    @Test
    fun primaryControlsAreAlignedAndMotionIsInstalled() {
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                putExtra("qa_screen", "home")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val minTouch = (48 * density + 0.5f).toInt()
                val nav = activity.findViewById<ViewGroup>(R.id.bottomNav)
                val navItems = intArrayOf(
                    R.id.mapNavButton,
                    R.id.routesNavButton,
                    R.id.transportNavButton,
                    R.id.favoritesNavButton
                ).map { activity.findViewById<TextView>(it) }

                assertTrue(nav.height >= minTouch)
                assertTrue(navItems.all { it.height >= minTouch })
                val widths = navItems.map { it.width }
                assertTrue("bottom navigation items are not equally aligned", widths.max() - widths.min() <= 2)
                assertTrue(navItems.all { !it.includeFontPadding })
                assertTrue(navItems.all { it.stateListAnimator != null })

                assertTrue(activity.findViewById<View>(R.id.locationButton).height >= minTouch)
                assertTrue(activity.findViewById<View>(R.id.settingsButton).height >= minTouch)
                assertNotNull(activity.findViewById<ViewGroup>(R.id.searchPanel).layoutTransition)
                assertNotNull(activity.findViewById<ViewGroup>(R.id.routeResultsPanel).layoutTransition)

                activity.findViewById<View>(R.id.compactSearchRow).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val minTouch = (48 * density + 0.5f).toInt()
                val route = activity.findViewById<View>(R.id.routeButton)
                val clearFrom = activity.findViewById<View>(R.id.clearFromButton)
                val clearTo = activity.findViewById<View>(R.id.clearToButton)
                assertTrue(route.visibility == View.VISIBLE)
                assertTrue(route.height >= minTouch)
                assertTrue(clearFrom.height >= minTouch)
                assertTrue(clearTo.height >= minTouch)
                assertNotNull(route.stateListAnimator)
                assertTrue(abs(route.scaleX - 1f) < 0.01f)
                assertTrue(abs(route.scaleY - 1f) < 0.01f)
            }
        } finally {
            scenario.close()
        }
    }
}

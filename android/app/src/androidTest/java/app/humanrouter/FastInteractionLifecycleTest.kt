package app.humanrouter

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastInteractionLifecycleTest {
    @Test
    fun activeLifecycleInstallsFastSearchAndRouteControllers() {
        val context = ApplicationProvider.getApplicationContext<VremyaHodomApp>()
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).apply {
                putExtra("qa_screen", "home")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        try {
            scenario.onActivity { activity ->
                assertTrue("FastSearchController is not installed", FastSearchController.isInstalled(activity))
                assertTrue("FastRoutePlanner is not installed", FastRoutePlanner.isInstalled(activity))
            }
        } finally {
            scenario.close()
        }
    }
}

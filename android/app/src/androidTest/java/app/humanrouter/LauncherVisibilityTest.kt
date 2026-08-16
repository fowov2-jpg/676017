package app.humanrouter

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherVisibilityTest {
    @Test
    fun mainActivityIsEnabledAndResolvableFromLauncher() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = context.packageManager
        val component = ComponentName(context, MainActivity::class.java)
        val info = packageManager.getActivityInfo(component, 0)
        assertTrue("MainActivity must be enabled", info.enabled)

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }
        val resolved = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(
            "The app must expose a launcher entry",
            resolved.any { it.activityInfo.name == MainActivity::class.java.name }
        )
        val direct = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(MainActivity::class.java.name, direct?.activityInfo?.name)
    }
}

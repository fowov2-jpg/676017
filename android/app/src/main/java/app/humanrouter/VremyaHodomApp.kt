package app.humanrouter

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class VremyaHodomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = if (AppPreferences.isDarkTheme(this)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)

        // One lifecycle owner for cross-cutting UI behavior. The previous chain of patch objects
        // remains in history for comparison but is no longer installed in production execution.
        VremyaHodomUiCoordinator.install(this)
        RuntimeUpdateScheduler.schedule(this)
    }
}

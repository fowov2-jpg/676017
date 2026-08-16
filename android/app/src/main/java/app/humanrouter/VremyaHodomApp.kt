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

        // One registered lifecycle owner coordinates visual defaults and route/journey/map behavior.
        VremyaHodomLifecycleCoordinator.install(this)
        RuntimeUpdateScheduler.schedule(this)
    }
}

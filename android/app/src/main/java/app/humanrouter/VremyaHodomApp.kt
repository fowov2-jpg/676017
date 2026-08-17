package app.humanrouter

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

class VremyaHodomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installSentryIfConfigured()

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

    private fun installSentryIfConfigured() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return

        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.tracesSampleRate = 0.10
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        }

        Sentry.setTag("app_component", "android")
        if (BuildConfig.GIT_SHA.isNotBlank()) {
            Sentry.setTag("git_sha", BuildConfig.GIT_SHA.take(12))
        }
    }
}

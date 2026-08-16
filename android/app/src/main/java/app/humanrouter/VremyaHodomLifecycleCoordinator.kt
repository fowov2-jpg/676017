package app.humanrouter

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The single Application lifecycle entry-point for phone UI behavior.
 *
 * UiPolish is a one-shot visual helper (touch targets, font padding, press animators and layout
 * transitions); it is not registered as a lifecycle callback. The route/journey/map coordinator is
 * driven through this owner as well, so the app has one lifecycle registration instead of the old
 * chain of independent runtime patch controllers.
 */
internal object VremyaHodomLifecycleCoordinator : Application.ActivityLifecycleCallbacks {
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) UiPolish.install(activity)
        VremyaHodomUiCoordinator.onActivityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        VremyaHodomUiCoordinator.onActivityPaused(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        VremyaHodomUiCoordinator.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

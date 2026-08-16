package app.humanrouter

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The single Application lifecycle entry-point for phone UI behavior.
 *
 * Visual helpers are one-shot components, not independent lifecycle registrations. Route/journey/map
 * behavior and the reference product presentation are all driven through this owner.
 */
internal object VremyaHodomLifecycleCoordinator : Application.ActivityLifecycleCallbacks {
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) UiPolish.install(activity)
        VremyaHodomUiCoordinator.onActivityResumed(activity)
        if (activity is MainActivity) {
            ReferenceProductUi.install(activity)
            ReferenceProductUiRefinement.install(activity)
        }
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

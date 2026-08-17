package app.humanrouter

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Single Application lifecycle entry-point for phone and tablet UI behavior. */
internal object VremyaHodomLifecycleCoordinator : Application.ActivityLifecycleCallbacks {
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) ResponsiveMotion.install(activity)
        VremyaHodomUiCoordinator.onActivityResumed(activity)
        if (activity is MainActivity) {
            // ResponsiveProductUi is the only owner allowed to compose screen geometry/visibility.
            // ResponsiveMotion adds geometry-free feedback. ResponsiveViewportGuard only clamps
            // two viewport invariants before draw and becomes a no-op once they are satisfied.
            ResponsiveProductUi.install(activity)
            ResponsiveViewportGuard.install(activity)
            // GPS progress only binds passenger state to already-composed trip chrome. It never
            // owns sheet geometry or screen visibility.
            TripProgressUiController.install(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) TripProgressUiController.pause(activity)
        VremyaHodomUiCoordinator.onActivityPaused(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is MainActivity) TripProgressUiController.destroy(activity)
        VremyaHodomUiCoordinator.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

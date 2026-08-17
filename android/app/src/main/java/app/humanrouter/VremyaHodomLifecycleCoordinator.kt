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
            // GPS progress binders only update content inside already-composed trip views; they do
            // not own sheet geometry or visibility. The same state receives real foreground
            // LocationManager samples and deterministic CI replay samples.
            TripProgressUiController.install(activity)
            TripProgressDetailBinder.install(activity)
            PassengerGpsProgressCoordinator.resume(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) {
            PassengerGpsProgressCoordinator.pause(activity)
            TripProgressDetailBinder.pause(activity)
            TripProgressUiController.pause(activity)
        }
        VremyaHodomUiCoordinator.onActivityPaused(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is MainActivity) {
            PassengerGpsProgressCoordinator.destroy(activity)
            TripProgressDetailBinder.destroy(activity)
            TripProgressUiController.destroy(activity)
        }
        VremyaHodomUiCoordinator.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

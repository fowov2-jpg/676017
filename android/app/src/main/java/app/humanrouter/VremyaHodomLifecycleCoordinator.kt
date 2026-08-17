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
            // ResponsiveProductUi composes the screen; RouteSheetInteractionCoordinator is the
            // final owner of the draggable route-sheet size so automatic restyling cannot fight
            // the user's gesture. ResponsiveViewportGuard only protects the settings entrance.
            ResponsiveProductUi.install(activity)
            ResponsiveViewportGuard.install(activity)
            RouteSheetInteractionCoordinator.install(activity)
            // Typed stop/station symbols and the compact "Отсюда / Сюда" sheet are map-owned UI.
            // They consume the same local NearbyRepository/runtime data as routing and never fake
            // vehicle locations.
            TransitStopMapController.install(activity)
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
            TransitStopMapController.destroy(activity)
            RouteSheetInteractionCoordinator.destroy(activity)
        }
        VremyaHodomUiCoordinator.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

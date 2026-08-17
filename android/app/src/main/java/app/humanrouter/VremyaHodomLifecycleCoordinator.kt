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
            // Core search/routing interactions must be installed by the single active lifecycle owner.
            // These controllers used to be installed by VremyaHodomSafeRuntimeFixes. When the app
            // moved to this coordinator, omitting them silently restored MainActivity's legacy
            // Photon-only search and synchronous planRouteNow() path. Install them here so typed
            // addresses use the resilient resolver and the first route uses the bounded fast preview.
            FastSearchController.install(activity)
            FastRoutePlanner.install(activity)

            // ResponsiveProductUi composes the screen; RouteSheetInteractionCoordinator is the
            // final owner of the draggable route-sheet size so automatic restyling cannot fight
            // the user's gesture. ResponsiveViewportGuard only protects the settings entrance.
            ResponsiveProductUi.install(activity)
            ResponsiveViewportGuard.install(activity)
            RouteSheetInteractionCoordinator.install(activity)
            // Typed stop/station symbols use a separate map spatial index, so the "Рядом" card can
            // stay short while the map shows a useful number of tappable transport points.
            TransitStopMapControllerV3.install(activity)
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
            TransitStopMapControllerV3.destroy(activity)
            RouteSheetInteractionCoordinator.destroy(activity)
        }
        VremyaHodomUiCoordinator.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

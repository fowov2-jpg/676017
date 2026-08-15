package app.humanrouter

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.humanrouter.routing.LastPlanStore
import java.util.WeakHashMap

/**
 * Defers the runtime UX controller until MainActivity is fully resumed and its content view exists.
 * It also notices a newly selected route and immediately kicks the controller, so route colours and
 * presentation do not wait for the periodic active-trip refresh tick.
 */
internal object VremyaHodomSafeRuntimeFixes : Application.ActivityLifecycleCallbacks {
    private class WatchState {
        var lastRouteId: String? = null
        var resumed: Boolean = false
        lateinit var runnable: Runnable
    }

    private val initialized = WeakHashMap<Activity, Boolean>()
    private val watches = WeakHashMap<MainActivity, WatchState>()
    private val handler = Handler(Looper.getMainLooper())

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        if (initialized.put(activity, true) != true) {
            VremyaHodomRuntimeFixes.onActivityCreated(activity, null)
        }
        VremyaHodomRuntimeFixes.onActivityResumed(activity)

        val state = watches.getOrPut(activity) {
            WatchState().also { watch ->
                watch.runnable = object : Runnable {
                    override fun run() {
                        if (!watch.resumed || activity.isFinishing || activity.isDestroyed) return
                        val routeId = TripLiveState.current()?.route?.id
                            ?: ActiveTripStore.load(activity)?.route?.id
                            ?: LastPlanStore.seed?.route?.id
                        if (routeId != null && routeId != watch.lastRouteId) {
                            watch.lastRouteId = routeId
                            VremyaHodomRuntimeFixes.onActivityResumed(activity)
                        }
                        handler.postDelayed(this, ROUTE_WATCH_INTERVAL_MS)
                    }
                }
            }
        }
        state.resumed = true
        handler.removeCallbacks(state.runnable)
        handler.post(state.runnable)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) {
            watches[activity]?.let { state ->
                state.resumed = false
                handler.removeCallbacks(state.runnable)
            }
        }
        if (initialized.containsKey(activity)) VremyaHodomRuntimeFixes.onActivityPaused(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is MainActivity) {
            watches.remove(activity)?.let { state ->
                state.resumed = false
                handler.removeCallbacks(state.runnable)
            }
        }
        if (initialized.remove(activity) != null) VremyaHodomRuntimeFixes.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private const val ROUTE_WATCH_INTERVAL_MS = 500L
}

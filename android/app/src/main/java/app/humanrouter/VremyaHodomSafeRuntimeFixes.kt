package app.humanrouter

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.WeakHashMap

/**
 * Defers the runtime UX controller until MainActivity is fully resumed and its content view exists.
 * This avoids touching views from ActivityLifecycleCallbacks.onActivityCreated on platform versions
 * where that callback can run before the activity has finished installing its view hierarchy.
 */
internal object VremyaHodomSafeRuntimeFixes : Application.ActivityLifecycleCallbacks {
    private val initialized = WeakHashMap<Activity, Boolean>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        if (initialized.put(activity, true) != true) {
            VremyaHodomRuntimeFixes.onActivityCreated(activity, null)
        }
        VremyaHodomRuntimeFixes.onActivityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (initialized.containsKey(activity)) VremyaHodomRuntimeFixes.onActivityPaused(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (initialized.remove(activity) != null) VremyaHodomRuntimeFixes.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

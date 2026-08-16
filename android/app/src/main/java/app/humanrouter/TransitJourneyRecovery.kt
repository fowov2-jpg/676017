package app.humanrouter

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.util.WeakHashMap

/**
 * Repairs the small lifecycle race where MainActivity or the legacy transport decorator can rebuild
 * the active-trip panel after TransitJourneyUiV2 has already attached its strip. The recovery
 * controller is intentionally narrow: it only restores the V2 strip when a route panel is visible
 * and lets TransitJourneyVisibilityGuard remain the authority for which strip is shown.
 */
internal object TransitJourneyRecovery {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    @Synchronized
    fun kick(activity: MainActivity) {
        controllers[activity]?.kick()
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) {
        private val root = activity.findViewById<ViewGroup>(R.id.root)
        private val panel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private var destroyed = false
        private var layoutPosted = false

        private val layoutRunnable = Runnable {
            layoutPosted = false
            ensureNow()
        }
        private val retryRunnable = Runnable { ensureNow() }
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { ensureSoon() }

        init {
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            kick()
        }

        fun destroy() {
            destroyed = true
            root.removeCallbacks(layoutRunnable)
            root.removeCallbacks(retryRunnable)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }

        fun kick() {
            if (destroyed) return
            root.removeCallbacks(retryRunnable)
            root.post(retryRunnable)
            RETRY_DELAYS_MS.forEach { delay -> root.postDelayed(retryRunnable, delay) }
        }

        private fun ensureSoon() {
            if (destroyed || layoutPosted) return
            layoutPosted = true
            root.post(layoutRunnable)
        }

        private fun ensureNow() {
            if (destroyed || activity.isFinishing || activity.isDestroyed) return
            if (panel.visibility != View.VISIBLE || panel.childCount == 0) return

            val v2 = descendants(panel)
                .filterIsInstance<HorizontalScrollView>()
                .firstOrNull {
                    it.contentDescription?.toString() == TransitJourneyVisibilityGuard.V2_DESCRIPTION
                }

            if (v2 == null) {
                TransitJourneyUiV2.refresh(activity)
            }
            TransitJourneyVisibilityGuard.enforce(activity)
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
            }
        }
    }

    private val RETRY_DELAYS_MS = longArrayOf(40L, 120L, 300L, 700L)
}

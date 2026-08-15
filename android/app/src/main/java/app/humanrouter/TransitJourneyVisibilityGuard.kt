package app.humanrouter

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.util.WeakHashMap

/**
 * Keeps the V2 journey strip authoritative while the legacy visual decorator is still present.
 *
 * The legacy decorator was written before V2 and hides sibling HorizontalScrollViews in active-trip
 * mode. Until that decorator is removed completely, this guard resolves the race after layout:
 * legacy strips are hidden and the V2 strip is made visible again. It posts only when a visibility
 * change is actually needed, so it cannot create a layout-listener loop by itself.
 */
internal object TransitJourneyVisibilityGuard {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    @Synchronized
    fun enforce(activity: MainActivity) {
        controllers[activity]?.enforceSoon()
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) {
        private val root = activity.findViewById<ViewGroup>(R.id.root)
        private val panel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private var posted = false
        private var destroyed = false

        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (needsChange()) enforceSoon()
        }

        init {
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            enforceSoon()
        }

        fun destroy() {
            destroyed = true
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }

        fun enforceSoon() {
            if (posted || destroyed) return
            posted = true
            root.post {
                posted = false
                if (!destroyed && !activity.isFinishing && !activity.isDestroyed) enforceNow()
            }
        }

        private fun needsChange(): Boolean {
            var needs = false
            descendants(panel).filterIsInstance<HorizontalScrollView>().forEach { scroll ->
                when (scroll.contentDescription?.toString().orEmpty()) {
                    V2_DESCRIPTION -> if (scroll.visibility != View.VISIBLE) needs = true
                    LEGACY_DESCRIPTION -> if (scroll.visibility != View.GONE) needs = true
                }
            }
            return needs
        }

        private fun enforceNow() {
            descendants(panel).filterIsInstance<HorizontalScrollView>().forEach { scroll ->
                when (scroll.contentDescription?.toString().orEmpty()) {
                    V2_DESCRIPTION -> if (scroll.visibility != View.VISIBLE) scroll.visibility = View.VISIBLE
                    LEGACY_DESCRIPTION -> if (scroll.visibility != View.GONE) scroll.visibility = View.GONE
                }
            }
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
            }
        }
    }

    internal const val V2_DESCRIPTION = "Этапы маршрута с линиями и переходами"
    private const val LEGACY_DESCRIPTION = "Схема транспорта маршрута"
}

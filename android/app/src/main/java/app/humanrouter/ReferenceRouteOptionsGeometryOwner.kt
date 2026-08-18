package app.humanrouter

import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Final geometry owner for the route-alternatives sheet only.
 *
 * MainActivity still owns route state and ResponsiveProductUi owns presentation. This guard only
 * restores the approved ROUTES sheet geometry if a later inset/layout pass rewrites it. It is
 * deliberately inactive as soon as route filters disappear, so active-trip sheet geometry remains
 * owned by the trip controllers and cannot enter a feedback loop with this owner.
 */
internal object ReferenceRouteOptionsGeometryOwner {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) {
        private val density = activity.resources.displayMetrics.density
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val routeSheet = activity.findViewById<LinearLayout>(R.id.routeResultsContainer)
        private val routeFilters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        private var destroyed = false
        private var reconcilePosted = false

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleReconcile()
        }

        init {
            root.addOnLayoutChangeListener(layoutListener)
            routeSheet.addOnLayoutChangeListener(layoutListener)
            routeFilters.addOnLayoutChangeListener(layoutListener)
            root.post(::scheduleReconcile)
        }

        fun destroy() {
            destroyed = true
            root.removeOnLayoutChangeListener(layoutListener)
            routeSheet.removeOnLayoutChangeListener(layoutListener)
            routeFilters.removeOnLayoutChangeListener(layoutListener)
        }

        private fun scheduleReconcile() {
            if (destroyed || reconcilePosted) return
            reconcilePosted = true
            root.post {
                reconcilePosted = false
                reconcile()
            }
        }

        private fun reconcile() {
            if (destroyed || !isRouteOptions()) return

            val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val widthDp = (widthPx / density).roundToInt().coerceAtLeast(1)
            val heightDp = (heightPx / density).roundToInt().coerceAtLeast(1)
            val tablet = widthDp >= 600
            val compact = widthDp < 380 || heightDp < 700
            val landscape = widthDp > heightDp
            val contentMarginDp = when {
                tablet -> ((widthDp - min(widthDp, 720)) / 2).coerceAtLeast(28)
                compact -> 12
                else -> 18
            }
            val ratio = when {
                tablet && landscape -> 0.58f
                tablet -> 0.52f
                compact -> 0.62f
                heightDp >= 850 -> 0.56f
                else -> 0.59f
            }
            val targetDp = (heightDp * ratio).roundToInt()
                .coerceIn(if (compact) 360 else 390, if (tablet) 620 else 540)
            val sideDp = if (tablet) max(contentMarginDp, (widthDp - 720) / 2) else contentMarginDp
            val systemBottom = ViewCompat.getRootWindowInsets(root)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                ?.bottom
                ?: 0
            val targetHeight = dp(targetDp)
            val targetSide = dp(sideDp)
            val targetBottom = max(dp(8), systemBottom + dp(6))

            val lp = routeSheet.layoutParams as? FrameLayout.LayoutParams ?: return
            if (
                lp.height == targetHeight &&
                lp.leftMargin == targetSide &&
                lp.rightMargin == targetSide &&
                lp.bottomMargin == targetBottom
            ) return

            lp.height = targetHeight
            lp.leftMargin = targetSide
            lp.rightMargin = targetSide
            lp.bottomMargin = targetBottom
            routeSheet.layoutParams = lp
        }

        private fun isRouteOptions(): Boolean =
            routeSheet.visibility == View.VISIBLE && routeFilters.visibility == View.VISIBLE

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }
}

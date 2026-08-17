package app.humanrouter

import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Final viewport invariant guard for transient animation/viewport edge cases.
 *
 * This is deliberately not a styling layer: it owns no colors, text, padding or hierarchy. It
 * only prevents (1) a side sheet from being translated completely outside the visible viewport,
 * (2) compact route options from consuming more than 59% of the window, and (3) tablet portrait
 * route options from becoming too short to keep the alternatives usable. All checks converge and
 * become no-ops as soon as their viewport invariant is satisfied.
 */
internal object ResponsiveViewportGuard {
    private val installed = WeakHashMap<MainActivity, Boolean>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (installed.put(activity, true) == true) return

        val root = activity.findViewById<FrameLayout>(R.id.root)
        val settingsScrim = activity.findViewById<View>(R.id.settingsScrim)
        val settingsPanel = activity.findViewById<View>(R.id.settingsPanel)
        val settingsScroll = settingsPanel.parent as? ScrollView
        val routeSheet = activity.findViewById<View>(R.id.routeResultsContainer)
        val routeFilters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        val primaryAction = activity.findViewById<View>(R.id.routePrimaryAction)
        val density = activity.resources.displayMetrics.density

        val preDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (activity.isFinishing || activity.isDestroyed) return true

                if (settingsScrim.visibility == View.VISIBLE && settingsScroll != null) {
                    val maxEntryOffset = 20f * density
                    if (settingsScroll.translationX > maxEntryOffset) {
                        settingsScroll.animate().cancel()
                        settingsScroll.translationX = maxEntryOffset
                        settingsScroll.animate()
                            .translationX(0f)
                            .setDuration(180L)
                            .start()
                    }
                }

                val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
                val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
                val widthDp = widthPx / density
                val heightDp = heightPx / density
                val compact = widthDp < 380f || heightDp < 700f
                val tabletPortrait = widthDp >= 600f && heightDp > widthDp
                val activeTrip = primaryAction.visibility == View.VISIBLE &&
                    primaryAction.contentDescription?.toString()?.contains("Заверш", ignoreCase = true) == true
                val routeOptions = routeSheet.visibility == View.VISIBLE &&
                    routeFilters.visibility == View.VISIBLE && !activeTrip

                if (routeOptions) {
                    val lp = routeSheet.layoutParams as? FrameLayout.LayoutParams
                    if (compact) {
                        val target = (heightPx * 0.59f).roundToInt()
                        if (lp != null && lp.height != target) {
                            lp.height = target
                            routeSheet.layoutParams = lp
                            return false
                        }
                    } else if (tabletPortrait) {
                        val minimum = (heightPx * 0.52f).roundToInt()
                        if (lp != null && lp.height < minimum) {
                            lp.height = minimum
                            routeSheet.layoutParams = lp
                            return false
                        }
                    }
                }
                return true
            }
        }
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
                installed.remove(activity)
                root.removeOnAttachStateChangeListener(this)
            }
        })
    }
}
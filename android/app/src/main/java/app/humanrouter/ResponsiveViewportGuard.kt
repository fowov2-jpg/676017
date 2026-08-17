package app.humanrouter

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Final viewport invariant guard for transient animation/viewport edge cases.
 *
 * The route sheet rule is intentionally one-shot per presentation: it chooses a compact initial
 * size, then gets out of the way so the user can freely drag the sheet through collapsed/medium/
 * expanded states. Large windows also get a bounded panel width instead of a stretched full-width
 * sheet. The guard owns no colors, text, padding or route content.
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
        var routeOptionsWereVisible = false

        fun dp(value: Int): Int = (value * density).roundToInt()

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
                val activeTrip = primaryAction.visibility == View.VISIBLE &&
                    primaryAction.contentDescription?.toString()?.contains("Заверш", ignoreCase = true) == true
                val routeOptions = routeSheet.visibility == View.VISIBLE &&
                    routeFilters.visibility == View.VISIBLE && !activeTrip

                if (!routeOptions) {
                    routeOptionsWereVisible = false
                    return true
                }

                if (!routeOptionsWereVisible) {
                    routeOptionsWereVisible = true
                    val lp = routeSheet.layoutParams as? FrameLayout.LayoutParams ?: return true
                    val targetDp = when {
                        widthDp >= 840f && widthDp > heightDp -> (heightDp * 0.44f).roundToInt().coerceIn(300, 360)
                        widthDp >= 600f -> min(420, (heightDp * 0.34f).roundToInt()).coerceAtLeast(320)
                        widthDp < 380f || heightDp < 700f -> (heightDp * 0.40f).roundToInt().coerceIn(280, 330)
                        else -> (heightDp * 0.38f).roundToInt().coerceIn(310, 360)
                    }
                    val targetHeight = dp(targetDp)
                    var changed = lp.height != targetHeight
                    lp.height = targetHeight

                    if (widthDp >= 600f) {
                        val maxWidthDp = if (widthDp >= 840f) 560 else 600
                        val targetWidth = min(widthPx - dp(48), dp(maxWidthDp))
                        changed = changed || lp.width != targetWidth || lp.gravity != (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                        lp.width = targetWidth
                        lp.leftMargin = 0
                        lp.rightMargin = 0
                        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    }

                    if (changed) {
                        routeSheet.layoutParams = lp
                        return false
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

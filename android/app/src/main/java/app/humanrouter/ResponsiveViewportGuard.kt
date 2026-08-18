package app.humanrouter

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Final viewport guard for the settings side sheet.
 *
 * Route-sheet sizing and gestures are owned by RouteSheetInteractionCoordinator and are never
 * touched here. ResponsiveProductUi provides the general settings styling; this guard only protects
 * the final settings entrance/width against callback ordering during tablet relayouts.
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
        val density = activity.resources.displayMetrics.density

        val preDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (activity.isFinishing || activity.isDestroyed) return true
                if (settingsScrim.visibility != View.VISIBLE || settingsScroll == null) return true

                val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
                val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
                val widthDp = widthPx / density
                val tablet = widthDp >= 600f

                if (tablet) {
                    val landscape = widthPx > heightPx
                    // A fixed 420dp cap made a 1280dp landscape tablet only 32.8% wide, too narrow
                    // for the settings labels and below the reference side-sheet contract. Keep the
                    // map visible while giving the settings surface a stable tablet proportion.
                    val targetWidth = (widthPx * if (landscape) 0.40f else 0.46f).roundToInt()
                    val lp = settingsScroll.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null && (
                            lp.width != targetWidth ||
                                lp.height != FrameLayout.LayoutParams.MATCH_PARENT ||
                                lp.gravity != Gravity.END ||
                                lp.leftMargin != 0
                            )) {
                        lp.width = targetWidth
                        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                        lp.gravity = Gravity.END
                        lp.leftMargin = 0
                        settingsScroll.layoutParams = lp
                        // Do not expose one stale frame with the previous narrow measurement.
                        return false
                    }
                }

                val maxEntryOffset = 20f * density
                if (settingsScroll.translationX > maxEntryOffset) {
                    settingsScroll.animate().cancel()
                    settingsScroll.translationX = maxEntryOffset
                    settingsScroll.animate()
                        .translationX(0f)
                        .setDuration(180L)
                        .start()
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

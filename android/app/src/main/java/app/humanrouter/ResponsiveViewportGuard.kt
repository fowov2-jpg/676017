package app.humanrouter

import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import java.util.WeakHashMap

/**
 * Narrow viewport guard for the transient settings entrance only.
 *
 * Route-sheet sizing and gestures are owned by RouteSheetInteractionCoordinator. Keeping this
 * guard geometry-free avoids two controllers competing over the same bottom sheet.
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

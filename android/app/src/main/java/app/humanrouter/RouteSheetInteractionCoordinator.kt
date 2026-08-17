package app.humanrouter

import android.animation.ValueAnimator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Final interaction owner for the route sheet.
 *
 * Route choices start in a compact medium state so the map remains the primary context. Until the
 * user grabs the handle, the coordinator also verifies that compact state immediately before draw;
 * this prevents an earlier presentation pass from flashing a tall sheet for a frame. On ACTION_DOWN
 * the height becomes fully user-owned and automatic clamping stops until route options are reopened.
 */
internal object RouteSheetInteractionCoordinator {
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
        private val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        private val filters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        private val primary = activity.findViewById<Button>(R.id.routePrimaryAction)

        private var routeOptionsVisible = false
        private var userOwnsHeight = false
        private var applying = false
        private var downY = 0f
        private var startHeight = 0
        private var animator: ValueAnimator? = null

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reconcileInitialRouteSize()
        }
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (activity.isFinishing || activity.isDestroyed) return@OnPreDrawListener true
            val routes = isRouteOptions()
            if (!routes) {
                routeOptionsVisible = false
                userOwnsHeight = false
                return@OnPreDrawListener true
            }
            if (!routeOptionsVisible) {
                routeOptionsVisible = true
                userOwnsHeight = false
            }
            if (!userOwnsHeight && applyMediumRouteGeometry()) return@OnPreDrawListener false
            true
        }

        init {
            sheet.addOnLayoutChangeListener(layoutListener)
            root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            installGesture()
            root.post(::reconcileInitialRouteSize)
        }

        fun destroy() {
            animator?.cancel()
            sheet.removeOnLayoutChangeListener(layoutListener)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            sheet.setOnTouchListener(null)
        }

        private fun isActiveTrip(): Boolean =
            primary.visibility == View.VISIBLE &&
                primary.contentDescription?.toString()?.contains("Заверш", ignoreCase = true) == true

        private fun isRouteOptions(): Boolean =
            sheet.visibility == View.VISIBLE && filters.visibility == View.VISIBLE && !isActiveTrip()

        private fun reconcileInitialRouteSize() {
            if (applying) return
            val routes = isRouteOptions()
            if (!routes) {
                routeOptionsVisible = false
                userOwnsHeight = false
                return
            }
            if (!routeOptionsVisible) {
                routeOptionsVisible = true
                userOwnsHeight = false
            }
            if (!userOwnsHeight) applyMediumRouteGeometry()
        }

        /** Returns true when layout params were changed and another layout pass is required. */
        private fun applyMediumRouteGeometry(): Boolean {
            if (applying) return false
            val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val widthDp = widthPx / density
            val heightDp = heightPx / density
            val tablet = widthDp >= 600f
            val landscape = widthDp > heightDp

            val targetDp = when {
                tablet && landscape -> (heightDp * 0.35f).roundToInt().coerceIn(270, 320)
                tablet -> min(420, (heightDp * 0.34f).roundToInt()).coerceAtLeast(320)
                widthDp < 380f || heightDp < 700f -> (heightDp * 0.36f).roundToInt().coerceIn(270, 310)
                else -> (heightDp * 0.35f).roundToInt().coerceIn(292, 340)
            }
            val targetHeight = dp(targetDp)
            val lp = sheet.layoutParams as? FrameLayout.LayoutParams ?: return false
            var changed = lp.height != targetHeight
            lp.height = targetHeight

            if (tablet) {
                val maxWidthDp = if (landscape) 560 else 600
                val targetWidth = min(widthPx - dp(48), dp(maxWidthDp))
                changed = changed || lp.width != targetWidth || lp.gravity != (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                lp.width = targetWidth
                lp.leftMargin = 0
                lp.rightMargin = 0
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }

            if (changed) {
                applying = true
                sheet.layoutParams = lp
                sheet.post { applying = false }
            }
            return changed
        }

        private fun installGesture() {
            sheet.setOnTouchListener { view, event ->
                if (view.visibility != View.VISIBLE) return@setOnTouchListener false
                val handleZone = dp(56)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (event.y > handleZone) return@setOnTouchListener false
                        animator?.cancel()
                        if (isRouteOptions()) userOwnsHeight = true
                        downY = event.rawY
                        startHeight = view.height.takeIf { it > 0 } ?: view.layoutParams.height
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (event.rawY - downY).roundToInt()
                        val heights = sheetHeights()
                        setHeight((startHeight - delta).coerceIn(heights.first(), heights.last()))
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val heights = sheetHeights()
                        val current = view.height
                        val target = heights.minByOrNull { abs(it - current) } ?: heights[1]
                        animateHeight(target)
                        true
                    }
                    else -> false
                }
            }
        }

        private fun sheetHeights(): IntArray {
            val rootHeight = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val widthDp = (root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels) / density
            val tablet = widthDp >= 600f
            val collapsed = dp(if (tablet) 190 else 164)
            val medium = if (tablet) {
                min(dp(420), max(dp(300), (rootHeight * 0.34f).roundToInt()))
            } else {
                max(dp(278), (rootHeight * 0.34f).roundToInt())
            }
            val expanded = max(medium + dp(92), min(dp(if (tablet) 640 else 560), (rootHeight * 0.56f).roundToInt()))
            return intArrayOf(collapsed, medium, expanded)
        }

        private fun setHeight(height: Int) {
            if (sheet.layoutParams.height == height) return
            applying = true
            sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
            applying = false
        }

        private fun animateHeight(target: Int) {
            val start = sheet.height.takeIf { it > 0 } ?: sheet.layoutParams.height
            if (start == target) return
            animator?.cancel()
            animator = ValueAnimator.ofInt(start, target).apply {
                duration = 180L
                addUpdateListener { animation -> setHeight(animation.animatedValue as Int) }
                start()
            }
        }

        private fun dp(value: Int): Int = (value * density).roundToInt()
    }
}

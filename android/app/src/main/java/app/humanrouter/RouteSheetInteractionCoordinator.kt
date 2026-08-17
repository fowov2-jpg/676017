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
 * The presentation layer keeps enough measured height for the fully expanded content. This owner
 * exposes only a compact portion initially by translating the sheet below the viewport, then moves
 * that same already-laid-out surface through collapsed / medium / expanded offsets. This avoids
 * relayout fights and keeps drags smooth. Once route options close, translation is reset so active
 * trip and error sheets are never inherited from the route-choice offset.
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
        private var userOwnsOffset = false
        private var downY = 0f
        private var startTranslation = 0f
        private var animator: ValueAnimator? = null

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reconcileInitialOffset()
        }
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (activity.isFinishing || activity.isDestroyed) return@OnPreDrawListener true
            reconcileInitialOffset()
            true
        }

        init {
            sheet.addOnLayoutChangeListener(layoutListener)
            root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            installGesture()
            root.post(::reconcileInitialOffset)
        }

        fun destroy() {
            animator?.cancel()
            sheet.removeOnLayoutChangeListener(layoutListener)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            sheet.setOnTouchListener(null)
            sheet.translationY = 0f
        }

        private fun isActiveTrip(): Boolean =
            primary.visibility == View.VISIBLE &&
                primary.text?.toString()?.contains("Заверш", ignoreCase = true) == true

        private fun isRouteOptions(): Boolean =
            sheet.visibility == View.VISIBLE && filters.visibility == View.VISIBLE && !isActiveTrip()

        private fun reconcileInitialOffset() {
            val routes = isRouteOptions()
            if (!routes) {
                if (routeOptionsVisible || sheet.translationY != 0f) {
                    animator?.cancel()
                    sheet.translationY = 0f
                }
                routeOptionsVisible = false
                userOwnsOffset = false
                return
            }
            if (!routeOptionsVisible) {
                routeOptionsVisible = true
                userOwnsOffset = false
            }
            applyTabletWidthIfNeeded()
            if (!userOwnsOffset && sheet.height > 0) {
                sheet.translationY = offsets().medium
            }
        }

        private data class Offsets(val expanded: Float, val medium: Float, val collapsed: Float) {
            fun ordered(): List<Float> = listOf(expanded, medium, collapsed)
        }

        private fun offsets(): Offsets {
            val rootHeight = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val sheetHeight = sheet.height.takeIf { it > 0 } ?: max(1, sheet.layoutParams.height)
            val widthDp = (root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels) / density
            val heightDp = rootHeight / density
            val tablet = widthDp >= 600f
            val landscape = widthDp > heightDp

            val mediumVisibleDp = when {
                tablet && landscape -> (heightDp * 0.35f).roundToInt().coerceIn(270, 320)
                tablet -> min(420, (heightDp * 0.34f).roundToInt()).coerceAtLeast(320)
                widthDp < 380f || heightDp < 700f -> (heightDp * 0.36f).roundToInt().coerceIn(270, 310)
                else -> (heightDp * 0.35f).roundToInt().coerceIn(292, 340)
            }
            val collapsedVisibleDp = if (tablet) 190 else 164
            val mediumVisible = min(sheetHeight, dp(mediumVisibleDp))
            val collapsedVisible = min(mediumVisible, dp(collapsedVisibleDp))
            return Offsets(
                expanded = 0f,
                medium = (sheetHeight - mediumVisible).coerceAtLeast(0).toFloat(),
                collapsed = (sheetHeight - collapsedVisible).coerceAtLeast(0).toFloat()
            )
        }

        private fun applyTabletWidthIfNeeded() {
            val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val widthDp = widthPx / density
            if (widthDp < 600f) return
            val landscape = widthPx > heightPx
            val maxWidthDp = if (landscape) 560 else 600
            val targetWidth = min(widthPx - dp(48), dp(maxWidthDp))
            val lp = sheet.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.width == targetWidth && lp.gravity == (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) && lp.leftMargin == 0 && lp.rightMargin == 0) return
            lp.width = targetWidth
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            sheet.layoutParams = lp
        }

        private fun installGesture() {
            sheet.setOnTouchListener { view, event ->
                if (!isRouteOptions()) return@setOnTouchListener false
                val handleZone = dp(56)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (event.y > handleZone) return@setOnTouchListener false
                        animator?.cancel()
                        userOwnsOffset = true
                        downY = event.rawY
                        startTranslation = view.translationY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawY - downY
                        val positions = offsets()
                        view.translationY = (startTranslation + delta).coerceIn(positions.expanded, positions.collapsed)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val positions = offsets()
                        val current = view.translationY
                        val target = positions.ordered().minByOrNull { abs(it - current) } ?: positions.medium
                        animateTranslation(target)
                        true
                    }
                    else -> false
                }
            }
        }

        private fun animateTranslation(target: Float) {
            val start = sheet.translationY
            if (abs(start - target) < 0.5f) return
            animator?.cancel()
            animator = ValueAnimator.ofFloat(start, target).apply {
                duration = 190L
                addUpdateListener { animation -> sheet.translationY = animation.animatedValue as Float }
                start()
            }
        }

        private fun dp(value: Int): Int = (value * density).roundToInt()
    }
}

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
 * Interaction and final geometry owner for the route-options bottom sheet.
 *
 * The sheet keeps enough measured content for an expanded state, but the user initially sees only
 * a compact map-first portion. Dragging the handle moves the same laid-out surface through
 * collapsed / medium / expanded offsets. Older presentation code may still request a legacy fixed
 * height while route data or system insets are rendered; this coordinator reconciles that request
 * back to the responsive expanded capacity before a frame is allowed to draw, so LayoutParams no
 * longer depend on callback ordering or font scale.
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
        private val filtersLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reconcileInitialOffset()
        }
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (activity.isFinishing || activity.isDestroyed) return@OnPreDrawListener true

            // A late WindowInsets / legacy route-state callback can put the sheet back to a fixed
            // 228-324dp height after ResponsiveProductUi already applied the correct ratio. Merely
            // calling requestLayout here and returning true exposes one bad frame and, on large-text
            // devices, ActivityScenario can observe that stale measured height. If geometry changed
            // (or LayoutParams are correct but the old measurement is still on screen), cancel this
            // draw. Android performs the requested traversal and the next pre-draw is admitted only
            // once measured geometry matches the responsive policy.
            val geometryPending = reconcileInitialOffset()
            !geometryPending
        }

        init {
            sheet.addOnLayoutChangeListener(layoutListener)
            filters.addOnLayoutChangeListener(filtersLayoutListener)
            root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            installGesture()
            root.post(::reconcileInitialOffset)
        }

        fun destroy() {
            animator?.cancel()
            sheet.removeOnLayoutChangeListener(layoutListener)
            filters.removeOnLayoutChangeListener(filtersLayoutListener)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            sheet.setOnTouchListener(null)
            sheet.translationY = 0f
        }

        private fun isActiveTrip(): Boolean =
            primary.visibility == View.VISIBLE &&
                primary.text?.toString()?.contains("Заверш", ignoreCase = true) == true

        private fun isRouteOptions(): Boolean =
            sheet.visibility == View.VISIBLE && filters.visibility == View.VISIBLE && !isActiveTrip()

        /**
         * @return true when another layout traversal is required before the current frame is safe.
         */
        private fun reconcileInitialOffset(): Boolean {
            val routes = isRouteOptions()
            if (!routes) {
                if (routeOptionsVisible || sheet.translationY != 0f) {
                    animator?.cancel()
                    sheet.translationY = 0f
                }
                routeOptionsVisible = false
                userOwnsOffset = false
                return false
            }
            if (!routeOptionsVisible) {
                routeOptionsVisible = true
                userOwnsOffset = false
            }

            // MainActivity still owns route state and may set a legacy 228-324dp height while it is
            // rebuilding route cards or applying system insets. The approved route-options reference
            // needs room for endpoints, filters and alternatives, especially at 1.25x text and on
            // tablets. Correct the underlying measured surface here; map-first behavior is controlled
            // by translation below, not by throwing away the expanded content area.
            val heightPending = ensureExpandedSheetCapacity()
            val widthPending = applyTabletWidthIfNeeded()
            val geometryPending = heightPending || widthPending

            if (!userOwnsOffset && sheet.height > 0 && !geometryPending) {
                sheet.translationY = offsets().medium
            }
            return geometryPending
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

            // Map-first default: roughly one third of the viewport remains occupied by route choices.
            // Full route details are still available by dragging the handle upward.
            val mediumVisibleDp = when {
                tablet && landscape -> (heightDp * 0.30f).roundToInt().coerceIn(230, 280)
                tablet -> min(380, (heightDp * 0.30f).roundToInt()).coerceAtLeast(290)
                widthDp < 380f || heightDp < 700f -> (heightDp * 0.32f).roundToInt().coerceIn(245, 285)
                else -> (heightDp * 0.33f).roundToInt().coerceIn(270, 320)
            }
            val collapsedVisibleDp = if (tablet) 168 else 150
            val mediumVisible = min(sheetHeight, dp(mediumVisibleDp))
            val collapsedVisible = min(mediumVisible, dp(collapsedVisibleDp))
            return Offsets(
                expanded = 0f,
                medium = (sheetHeight - mediumVisible).coerceAtLeast(0).toFloat(),
                collapsed = (sheetHeight - collapsedVisible).coerceAtLeast(0).toFloat()
            )
        }

        /**
         * Keeps LayoutParams and the measured height on the same responsive value.
         *
         * Returning true when LayoutParams already contain the target but the current measurement is
         * stale is intentional: the pre-draw listener then blocks that stale frame until the pending
         * requestLayout has completed. Explicitly request that traversal when Android has retained a
         * stale measurement; otherwise pre-draw could keep rejecting the same frame indefinitely.
         */
        private fun ensureExpandedSheetCapacity(): Boolean {
            val rootHeight = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val rootWidth = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            if (rootHeight <= 0 || rootWidth <= 0) return false

            val widthDp = rootWidth / density
            val heightDp = rootHeight / density
            val tablet = widthDp >= 600f
            val landscape = widthDp > heightDp
            val compact = widthDp < 380f || heightDp < 700f

            val preferredRatio = when {
                tablet && landscape -> 0.58f
                tablet -> 0.52f
                compact -> 0.62f
                heightDp >= 850f -> 0.56f
                else -> 0.59f
            }
            val minimumRatio = if (heightDp < 700f) 0.58f else 0.50f
            val ratio = preferredRatio.coerceIn(minimumRatio, MAX_EXPANDED_RATIO)
            val targetHeight = (rootHeight * ratio).roundToInt()
                .coerceIn(
                    (rootHeight * minimumRatio).roundToInt(),
                    (rootHeight * MAX_EXPANDED_RATIO).roundToInt()
                )

            val lp = sheet.layoutParams as? FrameLayout.LayoutParams ?: return false
            var changed = false
            if (lp.height != targetHeight) {
                lp.height = targetHeight
                sheet.layoutParams = lp
                changed = true
            }

            // If the new LayoutParams were installed from an OnLayout/OnPreDraw callback, sheet.height
            // can still represent the previous traversal. Do not let that stale measurement draw,
            // but make sure a traversal is actually queued so this condition cannot become permanent.
            val measuredPending = sheet.isLaidOut && sheet.height > 0 && abs(sheet.height - targetHeight) > 1
            if (measuredPending && !sheet.isLayoutRequested) sheet.requestLayout()
            return changed || measuredPending
        }

        private fun applyTabletWidthIfNeeded(): Boolean {
            val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val widthDp = widthPx / density
            if (widthDp < 600f) return false
            val landscape = widthPx > heightPx
            val maxWidthDp = if (landscape) 540 else 580
            val targetWidth = min(widthPx - dp(48), dp(maxWidthDp))
            val lp = sheet.layoutParams as? FrameLayout.LayoutParams ?: return false
            if (
                lp.width == targetWidth &&
                lp.gravity == (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) &&
                lp.leftMargin == 0 &&
                lp.rightMargin == 0
            ) {
                val measuredPending = sheet.isLaidOut && sheet.width > 0 && abs(sheet.width - targetWidth) > 1
                if (measuredPending && !sheet.isLayoutRequested) sheet.requestLayout()
                return measuredPending
            }
            lp.width = targetWidth
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            sheet.layoutParams = lp
            return true
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

    private const val MAX_EXPANDED_RATIO = 0.66f
}

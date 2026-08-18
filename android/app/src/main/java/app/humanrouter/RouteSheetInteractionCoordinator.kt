package app.humanrouter

import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Interaction and final geometry/semantic owner for the route-options bottom sheet. */
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
        private val routePanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
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
        private val routePanelLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reconcileInitialOffset()
        }
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (activity.isFinishing || activity.isDestroyed) return@OnPreDrawListener true
            // Never expose a stale legacy height or a semantically clipped route chain for one frame.
            !reconcileInitialOffset()
        }

        init {
            sheet.addOnLayoutChangeListener(layoutListener)
            filters.addOnLayoutChangeListener(filtersLayoutListener)
            routePanel.addOnLayoutChangeListener(routePanelLayoutListener)
            root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            installGesture()
            root.post(::reconcileInitialOffset)
        }

        fun destroy() {
            animator?.cancel()
            sheet.removeOnLayoutChangeListener(layoutListener)
            filters.removeOnLayoutChangeListener(filtersLayoutListener)
            routePanel.removeOnLayoutChangeListener(routePanelLayoutListener)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            sheet.setOnTouchListener(null)
            sheet.translationY = 0f
        }

        private fun isActiveTrip(): Boolean =
            primary.visibility == View.VISIBLE &&
                primary.text?.toString()?.contains("Заверш", ignoreCase = true) == true

        private fun isRouteOptions(): Boolean =
            sheet.visibility == View.VISIBLE && filters.visibility == View.VISIBLE && !isActiveTrip()

        /** @return true when another layout traversal is required before the current frame is safe. */
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

            val semanticPending = ensureRouteSemanticText()
            val densityPending = ensureCompactPhoneRouteDensity()
            val heightPending = ensureExpandedSheetCapacity()
            val widthPending = applyTabletWidthIfNeeded()
            val geometryPending = semanticPending || densityPending || heightPending || widthPending

            if (!userOwnsOffset && sheet.height > 0 && !geometryPending) {
                sheet.translationY = offsets().medium
            }
            return geometryPending
        }

        /**
         * ReferenceVisualTuning historically compressed every route chain to one ellipsized line.
         * That made a visually present route lose its transport sequence, which 218235 explicitly
         * forbids. Keep the complete semantic chain, but use two compact lines on phones so three
         * alternatives fit without hiding more than 43% of the map viewport.
         */
        private fun ensureRouteSemanticText(): Boolean {
            var changed = false
            routeCards().forEach { card ->
                val chain = routeChain(card) ?: return@forEach
                if (chain.maxLines != 2) {
                    chain.maxLines = 2
                    changed = true
                }
                if (chain.ellipsize != null) {
                    chain.ellipsize = null
                    changed = true
                }
                if (chain.includeFontPadding) {
                    chain.includeFontPadding = false
                    changed = true
                }
                val targetSp = if (activity.resources.configuration.fontScale > 1.15f) 8.5f else 9.25f
                val targetPx = targetSp * activity.resources.displayMetrics.scaledDensity
                if (abs(chain.textSize - targetPx) > 0.5f) {
                    chain.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSp)
                    changed = true
                }
                val topPadding = dp(2)
                if (chain.paddingTop != topPadding || chain.paddingBottom != 0) {
                    chain.setPadding(chain.paddingLeft, topPadding, chain.paddingRight, 0)
                    changed = true
                }
            }
            if (changed && !routePanel.isLayoutRequested) routePanel.requestLayout()
            return changed
        }

        /**
         * The compact reference phone cannot simultaneously keep >50% map context and show three
         * 218235 alternatives if every card repeats the verbose aggregate meta row. The chain already
         * exposes walk/transit/transfer semantics and the card header owns total/arrival time, so hide
         * only that redundant aggregate line on phones and trim vertical chrome.
         */
        private fun ensureCompactPhoneRouteDensity(): Boolean {
            val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val widthDp = widthPx / density
            if (widthDp >= 600f) return false

            var changed = false
            routeCards().forEach { card ->
                val horizontal = dp(8)
                val vertical = dp(4)
                if (
                    card.paddingLeft != horizontal ||
                    card.paddingRight != horizontal ||
                    card.paddingTop != vertical ||
                    card.paddingBottom != vertical
                ) {
                    card.setPadding(horizontal, vertical, horizontal, vertical)
                    changed = true
                }
                (card.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    val targetMargin = dp(3)
                    if (lp.topMargin != targetMargin) {
                        lp.topMargin = targetMargin
                        card.layoutParams = lp
                        changed = true
                    }
                }

                val chain = routeChain(card)
                descendantTextViews(card)
                    .firstOrNull { text ->
                        text !== chain &&
                            (text.text?.toString()?.contains("ожидание", ignoreCase = true) == true ||
                                text.text?.toString()?.contains("пересад", ignoreCase = true) == true)
                    }
                    ?.let { meta ->
                        if (meta.visibility != View.GONE) {
                            meta.visibility = View.GONE
                            changed = true
                        }
                    }
            }
            if (changed && !routePanel.isLayoutRequested) routePanel.requestLayout()
            return changed
        }

        private fun routeChain(card: LinearLayout): TextView? =
            descendantTextViews(card).firstOrNull { it.text?.toString()?.contains('›') == true }

        private fun routeCards(): List<LinearLayout> = buildList {
            for (index in 0 until routePanel.childCount) {
                val child = routePanel.getChildAt(index)
                if (child is LinearLayout && child.isClickable) add(child)
            }
        }

        private fun descendantTextViews(view: View): Sequence<TextView> = sequence {
            if (view is TextView) yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    yieldAll(descendantTextViews(view.getChildAt(index)))
                }
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

            // MainActivitySmokeTest protects map-first composition at <=43% of the globally visible
            // root. A root-relative 40% target leaves enough inset headroom while compact cards keep
            // all three alternatives visible on the 360x800 reference phone.
            val mediumVisibleDp = when {
                tablet && landscape -> (heightDp * 0.30f).roundToInt().coerceIn(230, 280)
                tablet -> min(380, (heightDp * 0.30f).roundToInt()).coerceAtLeast(290)
                widthDp < 380f -> (heightDp * 0.40f).roundToInt().coerceIn(310, 324)
                heightDp < 700f -> (heightDp * 0.38f).roundToInt().coerceIn(250, 285)
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

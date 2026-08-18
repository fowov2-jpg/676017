package app.humanrouter

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Owns the single map control that remains available while a passenger trip is active.
 *
 * The approved active-trip reference keeps the current-location button on the map immediately above
 * the bottom journey sheet, while search/settings/navigation chrome is removed. ResponsiveProductUi
 * still owns every non-trip state; this owner snapshots the pre-trip LayoutParams, applies only the
 * active-trip lower-left position, switches to the reference navigation-arrow glyph, and restores
 * the normal HOME location control as soon as the trip mode ends.
 */
internal object ActiveTripMapControlOwner {
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

    private data class SavedState(
        val params: FrameLayout.LayoutParams,
        val visibility: Int,
        val translationX: Float,
        val translationY: Float
    )

    private class Controller(private val activity: MainActivity) {
        private val density = activity.resources.displayMetrics.density
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        private val primary = activity.findViewById<Button>(R.id.routePrimaryAction)
        private val location = activity.findViewById<ImageButton>(R.id.locationButton)
        private var saved: SavedState? = null
        private var applyPosted = false
        private var destroyed = false

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            schedule()
        }

        init {
            root.addOnLayoutChangeListener(layoutListener)
            sheet.addOnLayoutChangeListener(layoutListener)
            primary.addOnLayoutChangeListener(layoutListener)
            schedule()
            root.postDelayed(::reconcile, 280L)
            root.postDelayed(::reconcile, 900L)
        }

        fun destroy() {
            destroyed = true
            restore()
            root.removeOnLayoutChangeListener(layoutListener)
            sheet.removeOnLayoutChangeListener(layoutListener)
            primary.removeOnLayoutChangeListener(layoutListener)
        }

        private fun schedule() {
            if (destroyed || applyPosted) return
            applyPosted = true
            root.post {
                applyPosted = false
                reconcile()
            }
        }

        private fun isActiveTrip(): Boolean =
            sheet.visibility == View.VISIBLE &&
                primary.visibility == View.VISIBLE &&
                primary.text?.toString()?.contains("Заверш", ignoreCase = true) == true

        private fun reconcile() {
            if (destroyed || activity.isFinishing || activity.isDestroyed) return
            if (!isActiveTrip()) {
                restore()
                return
            }
            if (root.height <= 0 || sheet.height <= 0) {
                schedule()
                return
            }

            if (saved == null) {
                val current = location.layoutParams as? FrameLayout.LayoutParams ?: return
                saved = SavedState(
                    params = copyParams(current),
                    visibility = location.visibility,
                    translationX = location.translationX,
                    translationY = location.translationY
                )
            }

            val widthDp = (root.width / density).roundToInt().coerceAtLeast(1)
            val sizeDp = if (widthDp >= 600) 56 else 50
            val leftDp = if (widthDp >= 600) 28 else 18
            val sheetTop = (sheet.top + sheet.translationY).roundToInt()
            val bottomPx = (root.height - sheetTop + dp(12)).coerceAtLeast(dp(20))
            val current = location.layoutParams as? FrameLayout.LayoutParams ?: return
            val targetSize = dp(sizeDp)
            val targetGravity = Gravity.BOTTOM or Gravity.START
            if (
                current.width != targetSize ||
                current.height != targetSize ||
                current.gravity != targetGravity ||
                current.leftMargin != dp(leftDp) ||
                current.rightMargin != 0 ||
                current.topMargin != 0 ||
                current.bottomMargin != bottomPx
            ) {
                current.width = targetSize
                current.height = targetSize
                current.gravity = targetGravity
                current.leftMargin = dp(leftDp)
                current.rightMargin = 0
                current.topMargin = 0
                current.bottomMargin = bottomPx
                location.layoutParams = current
            }
            // Active-trip reference uses a directional navigation arrow, not the HOME crosshair.
            location.setImageResource(R.drawable.ic_trip_navigation)
            if (location.translationX != 0f) location.translationX = 0f
            if (location.translationY != 0f) location.translationY = 0f
            if (location.visibility != View.VISIBLE) location.visibility = View.VISIBLE
        }

        private fun restore() {
            val state = saved ?: return
            saved = null
            location.layoutParams = copyParams(state.params)
            location.translationX = state.translationX
            location.translationY = state.translationY
            location.setImageResource(R.drawable.ic_my_location)
            location.visibility = state.visibility
        }

        private fun copyParams(source: FrameLayout.LayoutParams): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(source.width, source.height, source.gravity).apply {
                leftMargin = source.leftMargin
                topMargin = source.topMargin
                rightMargin = source.rightMargin
                bottomMargin = source.bottomMargin
                marginStart = source.marginStart
                marginEnd = source.marginEnd
            }

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }
}

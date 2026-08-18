package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Final geometry owner for the PHONE home screen only.
 *
 * The approved 218231/218233 references are deliberately much denser than the legacy generic
 * responsive composition: the map must remain the dominant surface, the quick actions must not span
 * the full width, map controls live in the upper half, and Nearby + bottom navigation read as one
 * floating dock. This class never owns route/search/settings/trip geometry and never changes product
 * state or transport data.
 */
internal object ReferenceHomeGeometryOwner {
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
        private val searchPanel = activity.findViewById<LinearLayout>(R.id.searchPanel)
        private val compactSearchRow = activity.findViewById<LinearLayout>(R.id.compactSearchRow)
        private val compactSearchButton = activity.findViewById<TextView>(R.id.compactSearchButton)
        private val quickActions = activity.findViewById<LinearLayout>(R.id.quickActions)
        private val locationButton = activity.findViewById<ImageButton>(R.id.locationButton)
        private val settingsButton = activity.findViewById<ImageButton>(R.id.settingsButton)
        private val nearbyPanel = activity.findViewById<LinearLayout>(R.id.nearbyPanel)
        private val nearbyTitle = activity.findViewById<TextView>(R.id.nearbyTitle)
        private val nearbyState = activity.findViewById<TextView>(R.id.nearbyStateText)
        private val nearbyList = activity.findViewById<LinearLayout>(R.id.nearbyList)
        private val bottomNav = activity.findViewById<LinearLayout>(R.id.bottomNav)
        private val expandedSearch = activity.findViewById<View>(R.id.expandedSearchContent)
        private val routeSheet = activity.findViewById<LinearLayout>(R.id.routeResultsContainer)
        private val settingsScrim = activity.findViewById<FrameLayout>(R.id.settingsScrim)

        private var applyPosted = false
        private var destroyed = false

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleApply()
        }

        init {
            root.addOnLayoutChangeListener(layoutListener)
            root.post { applyIfHome() }
            // Map style/runtime/nearby rows arrive asynchronously. Re-apply only at bounded points;
            // normal layout callbacks keep the geometry convergent afterwards.
            root.postDelayed({ applyIfHome() }, 320L)
            root.postDelayed({ applyIfHome() }, 900L)
        }

        fun destroy() {
            destroyed = true
            root.removeOnLayoutChangeListener(layoutListener)
        }

        private fun scheduleApply() {
            if (destroyed || applyPosted) return
            applyPosted = true
            root.post {
                applyPosted = false
                applyIfHome()
            }
        }

        private fun applyIfHome() {
            if (destroyed || activity.isFinishing || activity.isDestroyed) return
            val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val widthDp = (widthPx / density).roundToInt().coerceAtLeast(1)
            val heightDp = (heightPx / density).roundToInt().coerceAtLeast(1)

            // The five approved references currently specify the phone composition. Keep tablets on
            // the existing responsive owner until their own reference geometry is approved.
            if (widthDp >= 600 || widthDp > heightDp) return
            if (!isHome()) return

            styleSearch(widthDp)
            styleQuickActions(widthDp)
            styleMapControls(widthDp, heightDp)
            styleHomeDock(widthDp)
        }

        private fun isHome(): Boolean =
            settingsScrim.visibility != View.VISIBLE &&
                expandedSearch.visibility != View.VISIBLE &&
                routeSheet.visibility != View.VISIBLE &&
                nearbyPanel.visibility == View.VISIBLE &&
                bottomNav.visibility == View.VISIBLE

        private fun styleSearch(widthDp: Int) {
            val targetWidth = min(widthDp - 44, 340).coerceAtLeast(280)
            val side = ((widthDp - targetWidth) / 2).coerceAtLeast(18)
            (searchPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                searchPanel.layoutParams = lp
            }
            searchPanel.apply {
                elevation = dp(10).toFloat()
                background = rounded(color(R.color.vh_surface_solid), 28f, color(R.color.vh_border), 0.45f)
                setPadding(dp(5), dp(2), dp(5), dp(2))
            }
            compactSearchRow.layoutParams = compactSearchRow.layoutParams.apply { height = dp(52) }
            compactSearchButton.apply {
                textSize = 16f
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.NORMAL)
            }
        }

        private fun styleQuickActions(widthDp: Int) {
            val targetWidth = min(widthDp - 52, 300).coerceAtLeast(260)
            val side = ((widthDp - targetWidth) / 2).coerceAtLeast(22)
            (quickActions.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                lp.height = dp(42)
                quickActions.layoutParams = lp
            }
            intArrayOf(R.id.homeQuickButton, R.id.workQuickButton, R.id.nearbyQuickButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    minimumHeight = dp(40)
                    textSize = 12.5f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.NORMAL)
                    compoundDrawablePadding = dp(5)
                    setPadding(dp(6), 0, dp(6), 0)
                    elevation = dp(4).toFloat()
                    background = rounded(color(R.color.vh_surface_solid), 21f, color(R.color.vh_border), 0.4f)
                }
            }
        }

        private fun styleMapControls(widthDp: Int, heightDp: Int) {
            val size = if (widthDp < 380) 48 else 50
            val right = if (widthDp < 380) 14 else 18
            val firstTop = (heightDp * 0.235f).roundToInt().coerceIn(150, 210)
            styleMapButton(locationButton, size, right, firstTop)
            styleMapButton(settingsButton, size, right, firstTop + size + 12)
        }

        private fun styleMapButton(button: ImageButton, sizeDp: Int, rightDp: Int, topDp: Int) {
            (button.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = dp(sizeDp)
                lp.height = dp(sizeDp)
                lp.gravity = Gravity.TOP or Gravity.END
                lp.rightMargin = dp(rightDp)
                lp.topMargin = dp(topDp)
                button.layoutParams = lp
            }
            button.apply {
                translationY = 0f
                elevation = dp(10).toFloat()
                background = rounded(color(R.color.vh_surface_solid), sizeDp / 2f, color(R.color.vh_border), 0.4f)
                imageTintList = ColorStateList.valueOf(color(R.color.vh_primary))
                setPadding(dp(13), dp(13), dp(13), dp(13))
            }
        }

        private fun styleHomeDock(widthDp: Int) {
            val dockWidth = min(widthDp - 40, 340).coerceAtLeast(292)
            val side = ((widthDp - dockWidth) / 2).coerceAtLeast(18)
            val navHeight = 68
            val navBottom = 8
            val rowCount = nearbyList.childCount.coerceAtMost(3)
            val populated = rowCount > 0 && nearbyState.visibility != View.VISIBLE
            val nearbyHeight = if (populated) 218 else 112

            (bottomNav.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                lp.height = dp(navHeight)
                lp.bottomMargin = dp(navBottom)
                bottomNav.layoutParams = lp
            }
            bottomNav.apply {
                elevation = dp(18).toFloat()
                background = dockBackground(topRadiusDp = 0f, bottomRadiusDp = 27f)
                setPadding(dp(4), dp(2), dp(4), dp(3))
            }
            intArrayOf(R.id.mapNavButton, R.id.routesNavButton, R.id.transportNavButton, R.id.favoritesNavButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    minimumHeight = dp(58)
                    textSize = 10f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    compoundDrawablePadding = dp(3)
                    maxLines = 1
                }
            }

            (nearbyPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                lp.height = dp(nearbyHeight)
                // No visual gap: Nearby and the navigation bar are one floating dock, as in 218231/218233.
                lp.bottomMargin = dp(navBottom + navHeight)
                nearbyPanel.layoutParams = lp
            }
            nearbyPanel.apply {
                elevation = dp(18).toFloat()
                background = dockBackground(topRadiusDp = 27f, bottomRadiusDp = 0f)
                setPadding(dp(14), dp(8), dp(14), dp(5))
            }
            nearbyTitle.apply {
                textSize = 18f
                includeFontPadding = false
                setTypeface(typeface, Typeface.BOLD)
            }
            nearbyState.apply {
                textSize = 12.5f
                includeFontPadding = false
            }

            for (index in 0 until nearbyList.childCount.coerceAtMost(3)) {
                styleNearbyRow(nearbyList.getChildAt(index))
            }
        }

        private fun styleNearbyRow(view: View) {
            val row = view as? LinearLayout ?: return
            row.minimumHeight = dp(53)
            row.setPadding(0, dp(1), 0, dp(1))

            (row.getChildAt(0) as? TextView)?.apply {
                val original = (tag as? String) ?: text?.toString().orEmpty().also { tag = it }
                val primaryMode = original.uppercase().substringBefore('/').trim()
                val icon = when (primaryMode) {
                    "Т" -> R.drawable.ic_tram
                    "М", "МЦК" -> R.drawable.ic_metro
                    "D", "Э" -> R.drawable.ic_transport
                    else -> R.drawable.ic_bus
                }
                val badgeColor = when (primaryMode) {
                    "Т" -> color(R.color.vh_tram)
                    "М" -> color(R.color.vh_metro)
                    "МЦК" -> color(R.color.vh_mcc)
                    "D" -> color(R.color.vh_mcd)
                    "Э" -> color(R.color.vh_train)
                    else -> color(R.color.vh_bus)
                }
                text = ""
                gravity = Gravity.CENTER
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                background = rounded(badgeColor, 11f)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = dp(40)
                    lp.height = dp(40)
                    layoutParams = lp
                }
            }

            (row.getChildAt(1) as? ViewGroup)?.let { copy ->
                (copy.getChildAt(0) as? TextView)?.apply {
                    textSize = 14f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                }
                (copy.getChildAt(1) as? TextView)?.apply {
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                }
            }
            (row.getChildAt(2) as? TextView)?.apply {
                textSize = 12f
                maxLines = 2
                includeFontPadding = false
            }
        }

        private fun dockBackground(topRadiusDp: Float, bottomRadiusDp: Float): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color(R.color.vh_surface_solid))
                cornerRadii = floatArrayOf(
                    dp(topRadiusDp).toFloat(), dp(topRadiusDp).toFloat(),
                    dp(topRadiusDp).toFloat(), dp(topRadiusDp).toFloat(),
                    dp(bottomRadiusDp).toFloat(), dp(bottomRadiusDp).toFloat(),
                    dp(bottomRadiusDp).toFloat(), dp(bottomRadiusDp).toFloat()
                )
                setStroke(dp(0.45f).coerceAtLeast(1), color(R.color.vh_border))
            }

        private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 0f): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadius = dp(radiusDp).toFloat()
                if (stroke != null && strokeDp > 0f) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
            }

        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        private fun dp(value: Float): Int = (value * density + 0.5f).toInt()
    }
}

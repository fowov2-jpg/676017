package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import java.time.Instant
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single responsive presentation owner for phone and tablet form factors.
 *
 * MainActivity owns routing/search state. This controller owns only visual composition. It is
 * intentionally convergent: it reacts to state/hierarchy/size changes, but never polls layout and
 * never installs a listener that continuously mutates the same geometry it observes.
 */
internal object ResponsiveProductUi {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    private enum class ScreenMode { HOME, SEARCH, ROUTES, TRIP, SETTINGS }

    private data class Metrics(
        val widthPx: Int,
        val heightPx: Int,
        val widthDp: Int,
        val heightDp: Int,
        val tablet: Boolean,
        val compact: Boolean,
        val landscape: Boolean,
        val contentMarginDp: Int,
        val navHeightDp: Int
    )

    private class Controller(private val activity: MainActivity) {
        private val density = activity.resources.displayMetrics.density
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val searchPanel = activity.findViewById<LinearLayout>(R.id.searchPanel)
        private val compactSearchRow = activity.findViewById<LinearLayout>(R.id.compactSearchRow)
        private val compactSearchButton = activity.findViewById<TextView>(R.id.compactSearchButton)
        private val expandedSearch = activity.findViewById<View>(R.id.expandedSearchContent)
        private val quickActions = activity.findViewById<LinearLayout>(R.id.quickActions)
        private val locationButton = activity.findViewById<ImageButton>(R.id.locationButton)
        private val settingsButton = activity.findViewById<ImageButton>(R.id.settingsButton)
        private val loadingPanel = activity.findViewById<LinearLayout>(R.id.loadingPanel)
        private val journeyRow = activity.findViewById<LinearLayout>(R.id.journeyRow)
        private val journeyStageText = activity.findViewById<TextView>(R.id.journeyStageText)
        private val statusText = activity.findViewById<TextView>(R.id.status)
        private val locationActionPanel = activity.findViewById<LinearLayout>(R.id.locationActionPanel)
        private val nearbyPanel = activity.findViewById<LinearLayout>(R.id.nearbyPanel)
        private val nearbyState = activity.findViewById<TextView>(R.id.nearbyStateText)
        private val nearbyList = activity.findViewById<LinearLayout>(R.id.nearbyList)
        private val routeSheet = activity.findViewById<LinearLayout>(R.id.routeResultsContainer)
        private val routeFilters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        private val routeFilterPanel = activity.findViewById<LinearLayout>(R.id.routeFiltersPanel)
        private val routePanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private val routePrimaryAction = activity.findViewById<Button>(R.id.routePrimaryAction)
        private val bottomNav = activity.findViewById<LinearLayout>(R.id.bottomNav)
        private val settingsScrim = activity.findViewById<FrameLayout>(R.id.settingsScrim)
        private val settingsPanel = activity.findViewById<LinearLayout>(R.id.settingsPanel)
        private val fromField = activity.findViewById<EditText>(R.id.fromField)
        private val toField = activity.findViewById<EditText>(R.id.toField)

        private var refreshPosted = false
        private var lastSignature = ""
        private var lastMode: ScreenMode? = null
        private var activeRouteId: String? = null
        private var endpointRouteId: String? = null
        private val animated = WeakHashMap<View, Boolean>()

        private val rootLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val signature = stateSignature()
            if (signature != lastSignature) {
                lastSignature = signature
                scheduleRefresh()
            }
        }

        init {
            root.addOnLayoutChangeListener(rootLayoutListener)
            routePanel.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    child?.post {
                        styleRoutePanel(metrics())
                        scheduleRefresh()
                    }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) = scheduleRefresh()
            })
            nearbyList.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    child?.post {
                        styleNearbyRow(child, metrics())
                        scheduleRefresh()
                    }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) = scheduleRefresh()
            })
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    root.removeOnLayoutChangeListener(rootLayoutListener)
                    controllers.remove(activity)
                    root.removeOnAttachStateChangeListener(this)
                }
            })
            root.post { refresh() }
            root.postDelayed({ refresh() }, 240L)
        }

        private fun scheduleRefresh() {
            if (refreshPosted) return
            refreshPosted = true
            root.post {
                refreshPosted = false
                refresh()
            }
        }

        private fun stateSignature(): String = buildString {
            append(root.width).append('x').append(root.height)
            append(':').append(expandedSearch.visibility)
            append(':').append(routeSheet.visibility)
            append(':').append(routeFilters.visibility)
            append(':').append(routePrimaryAction.visibility).append(':').append(routePrimaryAction.text)
            append(':').append(settingsScrim.visibility)
            append(':').append(nearbyPanel.visibility).append(':').append(nearbyList.childCount)
            append(':').append(loadingPanel.visibility)
            append(':').append(locationActionPanel.visibility)
        }

        private fun metrics(): Metrics {
            val widthPx = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val heightPx = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val widthDp = (widthPx / density).roundToInt().coerceAtLeast(1)
            val heightDp = (heightPx / density).roundToInt().coerceAtLeast(1)
            val tablet = widthDp >= 600
            val compact = widthDp < 380 || heightDp < 700
            val landscape = widthDp > heightDp
            val contentMargin = when {
                tablet -> ((widthDp - min(widthDp, 720)) / 2).coerceAtLeast(28)
                compact -> 12
                else -> 18
            }
            return Metrics(
                widthPx = widthPx,
                heightPx = heightPx,
                widthDp = widthDp,
                heightDp = heightDp,
                tablet = tablet,
                compact = compact,
                landscape = landscape,
                contentMarginDp = contentMargin,
                navHeightDp = if (compact) 64 else if (tablet) 74 else 70
            )
        }

        private fun screenMode(): ScreenMode = when {
            settingsScrim.visibility == View.VISIBLE -> ScreenMode.SETTINGS
            isActiveTrip() -> ScreenMode.TRIP
            routeSheet.visibility == View.VISIBLE && routeFilters.visibility == View.VISIBLE -> ScreenMode.ROUTES
            expandedSearch.visibility == View.VISIBLE -> ScreenMode.SEARCH
            else -> ScreenMode.HOME
        }

        private fun refresh() {
            if (activity.isFinishing || activity.isDestroyed) return
            val m = metrics()
            styleChrome(m)
            styleSettings(m)
            styleRoutePanel(m)
            styleFilters(m)
            styleLoading(m)
            for (index in 0 until nearbyList.childCount) styleNearbyRow(nearbyList.getChildAt(index), m)

            val mode = screenMode()
            when (mode) {
                ScreenMode.TRIP -> renderTrip(m)
                ScreenMode.ROUTES -> renderRoutes(m)
                ScreenMode.SEARCH -> renderSearch(m)
                ScreenMode.SETTINGS -> renderSettings(m)
                ScreenMode.HOME -> renderHome(m)
            }
            applyLocationActionGeometry(m, mode)
            enforceUnifiedStripVisibility(mode == ScreenMode.TRIP)
            if (mode != lastMode) animateModeEntrance(mode)
            lastMode = mode
        }

        private fun styleChrome(m: Metrics) {
            val surface = color(R.color.vh_surface_solid)
            val border = color(R.color.vh_border)
            val margin = dp(m.contentMarginDp)

            searchPanel.apply {
                elevation = dp(14).toFloat()
                background = rounded(surface, if (m.compact) 27f else 31f, border, 0.6f)
                setPadding(dp(7), dp(4), dp(7), dp(4))
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.leftMargin = margin
                    lp.rightMargin = margin
                    layoutParams = lp
                }
            }
            compactSearchRow.layoutParams = compactSearchRow.layoutParams.apply { height = dp(if (m.compact) 56 else 60) }
            compactSearchButton.apply {
                textSize = if (m.compact) 16f else 17.5f
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            (quickActions.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val quickMarginDp = if (m.tablet) m.contentMarginDp + 24 else if (m.compact) 16 else 26
                lp.leftMargin = dp(quickMarginDp)
                lp.rightMargin = dp(quickMarginDp)
                lp.height = dp(if (m.compact) 46 else 50)
                quickActions.layoutParams = lp
            }
            intArrayOf(R.id.homeQuickButton, R.id.workQuickButton, R.id.nearbyQuickButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = if (m.compact) 11.5f else 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    elevation = dp(5).toFloat()
                    background = rounded(surface, 23f, border, 0.5f)
                    minimumHeight = dp(if (m.compact) 44 else 48)
                    compoundDrawablePadding = dp(if (m.compact) 4 else 6)
                    setPadding(dp(if (m.compact) 5 else 8), 0, dp(if (m.compact) 5 else 8), 0)
                }
            }

            styleMapButton(locationButton, m)
            styleMapButton(settingsButton, m)

            nearbyPanel.apply {
                elevation = dp(22).toFloat()
                background = rounded(surface, if (m.compact) 26f else 30f, border, 0.5f)
                setPadding(dp(if (m.compact) 14 else 18), dp(9), dp(if (m.compact) 14 else 18), dp(8))
            }
            routeSheet.apply {
                elevation = dp(26).toFloat()
                background = rounded(surface, if (m.compact) 27f else 31f, border, 0.5f)
                setPadding(dp(if (m.compact) 12 else 16), dp(8), dp(if (m.compact) 12 else 16), dp(10))
            }

            bottomNav.apply {
                elevation = dp(24).toFloat()
                background = rounded(surface, if (m.compact) 25f else 28f, border, 0.5f)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    val navMarginDp = if (m.tablet) max(m.contentMarginDp + 48, (m.widthDp - 620) / 2) else m.contentMarginDp
                    lp.leftMargin = dp(navMarginDp)
                    lp.rightMargin = dp(navMarginDp)
                    lp.height = dp(m.navHeightDp)
                    layoutParams = lp
                }
            }
            intArrayOf(R.id.mapNavButton, R.id.routesNavButton, R.id.transportNavButton, R.id.favoritesNavButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    textSize = if (m.compact) 9.5f else 10.5f
                    minimumHeight = dp(m.navHeightDp - 8)
                    compoundDrawablePadding = dp(4)
                }
            }

            routePrimaryAction.apply {
                minimumHeight = dp(if (m.compact) 46 else 50)
                textSize = if (m.compact) 15f else 16.5f
                background = rounded(color(R.color.vh_primary), 18f)
            }

            fromField.maxLines = 1
            toField.maxLines = 1
            fromField.ellipsize = TextUtils.TruncateAt.END
            toField.ellipsize = TextUtils.TruncateAt.END
            if (!fromField.hasFocus()) {
                fromField.setSelection(0)
                fromField.scrollTo(0, 0)
            }
            if (!toField.hasFocus()) {
                toField.setSelection(0)
                toField.scrollTo(0, 0)
            }
        }

        private fun styleMapButton(button: ImageButton, m: Metrics) {
            val size = if (m.compact) 50 else 56
            button.apply {
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = dp(size)
                    lp.height = dp(size)
                    lp.rightMargin = dp(m.contentMarginDp)
                    layoutParams = lp
                }
                elevation = dp(14).toFloat()
                background = rounded(color(R.color.vh_surface_solid), size / 2f, color(R.color.vh_border), 0.5f)
                imageTintList = ColorStateList.valueOf(color(R.color.vh_primary))
                setPadding(dp(if (m.compact) 13 else 15), dp(if (m.compact) 13 else 15), dp(if (m.compact) 13 else 15), dp(if (m.compact) 13 else 15))
            }
        }

        private fun styleSettings(m: Metrics) {
            settingsScrim.setBackgroundColor(Color.argb(58, 8, 20, 39))
            val scroll = settingsPanel.parent as? ScrollView ?: return
            val targetDp = when {
                m.tablet -> min(420, (m.widthDp * 0.46f).roundToInt()).coerceAtLeast(340)
                m.widthDp < 380 -> (m.widthDp * 0.82f).roundToInt().coerceAtLeast(280)
                else -> (m.widthDp * 0.72f).roundToInt().coerceIn(286, 340)
            }
            (scroll.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = dp(targetDp)
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.END
                lp.leftMargin = 0
                scroll.layoutParams = lp
            }
            scroll.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color(R.color.vh_surface_solid))
                cornerRadii = floatArrayOf(dp(30).toFloat(), dp(30).toFloat(), 0f, 0f, 0f, 0f, dp(30).toFloat(), dp(30).toFloat())
            }
            settingsPanel.setPadding(dp(if (m.compact) 18 else 22), settingsPanel.paddingTop, dp(if (m.compact) 16 else 20), settingsPanel.paddingBottom)

            val header = settingsPanel.getChildAt(0) as? ViewGroup
            (header?.getChildAt(0) as? TextView)?.apply {
                textSize = if (m.compact) 19f else 21f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            activity.findViewById<TextView>(R.id.closeSettingsButton).apply {
                textSize = 26f
                val lp = layoutParams
                lp.width = dp(40)
                lp.height = dp(40)
                layoutParams = lp
            }

            val switchCopy = mapOf(
                R.id.showStopsSwitch to ("Показывать остановки" to "Отображать остановки общественного транспорта на карте"),
                R.id.showTransportSwitch to ("Показывать линии маршрута" to "Показывать транспортные сегменты выбранного маршрута"),
                R.id.darkThemeSwitch to ("Тёмная тема" to "Использовать тёмную тему интерфейса и карты"),
                R.id.lessWalkingSwitch to ("Меньше ходьбы" to "Предпочитать варианты с минимальной ходьбой"),
                R.id.avoidTransfersSwitch to ("Избегать пересадок" to "Предпочитать маршруты с меньшим числом пересадок")
            )
            switchCopy.forEach { (id, copy) ->
                activity.findViewById<SwitchCompat>(id).apply {
                    text = copy.first
                    contentDescription = "${copy.first}. ${copy.second}"
                    textSize = if (m.compact) 12.5f else 13.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    minimumHeight = dp(if (m.compact) 56 else 60)
                    setPadding(0, dp(3), 0, dp(3))
                }
            }
        }

        private fun styleLoading(m: Metrics) {
            loadingPanel.apply {
                elevation = dp(14).toFloat()
                background = rounded(color(R.color.vh_surface_solid), if (m.compact) 20f else 24f, color(R.color.vh_border), 0.5f)
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.leftMargin = dp(m.contentMarginDp)
                    lp.rightMargin = dp(m.contentMarginDp)
                    layoutParams = lp
                }
            }
            journeyStageText.apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = if (m.compact) 13f else 14.5f
            }
            statusText.apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = if (m.compact) 10.5f else 12f
            }
            if (journeyRow.childCount > 0) {
                val scene = journeyRow.getChildAt(0)
                if (scene !is android.widget.ImageView) {
                    val availableDp = (m.widthDp - 2 * m.contentMarginDp - 34).coerceAtLeast(260)
                    val sceneWidthDp = (availableDp * if (m.compact) 0.46f else 0.50f).roundToInt().coerceIn(126, if (m.tablet) 240 else 200)
                    val lp = scene.layoutParams as? LinearLayout.LayoutParams
                    if (lp != null && (lp.width != dp(sceneWidthDp) || lp.height != dp(if (m.compact) 82 else 94))) {
                        lp.width = dp(sceneWidthDp)
                        lp.height = dp(if (m.compact) 82 else 94)
                        lp.rightMargin = dp(8)
                        scene.layoutParams = lp
                    }
                }
            }
            journeyRow.layoutParams = journeyRow.layoutParams.apply { height = dp(if (m.compact) 86 else 98) }
        }

        private fun renderHome(m: Metrics) {
            removeActiveChrome()
            removeEndpointHeader()
            if (expandedSearch.visibility != View.VISIBLE) searchPanel.visibility = View.VISIBLE
            quickActions.visibility = View.VISIBLE
            bottomNav.visibility = View.VISIBLE
            locationButton.visibility = if (loadingPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            settingsButton.visibility = if (loadingPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            applyNearbyGeometry(m)
        }

        private fun renderSearch(m: Metrics) {
            removeActiveChrome()
            removeEndpointHeader()
            searchPanel.visibility = View.VISIBLE
            quickActions.visibility = View.GONE
            bottomNav.visibility = View.GONE
            nearbyPanel.visibility = View.GONE
            locationButton.visibility = if (loadingPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            settingsButton.visibility = if (loadingPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val searchMargin = if (m.compact) 10 else m.contentMarginDp
            (searchPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(searchMargin)
                lp.rightMargin = dp(searchMargin)
                searchPanel.layoutParams = lp
            }
        }

        private fun renderSettings(m: Metrics) {
            removeActiveChrome()
            removeEndpointHeader()
            applyNearbyGeometry(m)
            val scroll = settingsPanel.parent as? ScrollView ?: return
            if (lastMode != ScreenMode.SETTINGS) {
                settingsScrim.alpha = 0f
                settingsScrim.animate().alpha(1f).setDuration(160L).start()
                scroll.translationX = scroll.width.takeIf { it > 0 }?.toFloat() ?: dp(300).toFloat()
                scroll.animate().translationX(0f).setDuration(220L).start()
            }
        }

        private fun renderRoutes(m: Metrics) {
            removeActiveChrome()
            searchPanel.visibility = View.GONE
            quickActions.visibility = View.GONE
            locationButton.visibility = View.GONE
            settingsButton.visibility = View.GONE
            bottomNav.visibility = View.GONE
            nearbyPanel.visibility = View.GONE
            ensureEndpointHeader(m)
            applyRouteOptionsGeometry(m)
        }

        private fun renderTrip(m: Metrics) {
            searchPanel.visibility = View.GONE
            quickActions.visibility = View.GONE
            locationButton.visibility = View.GONE
            settingsButton.visibility = View.GONE
            bottomNav.visibility = View.GONE
            nearbyPanel.visibility = View.GONE
            removeEndpointHeader()
            ensureActiveChrome(m)
            clearLegacyActiveHeader()
            applyActiveGeometry(m)
        }

        private fun applyNearbyGeometry(m: Metrics) {
            if (nearbyPanel.visibility != View.VISIBLE) return
            val state = nearbyState.text?.toString().orEmpty()
            val compactState = nearbyList.childCount == 0 ||
                state.contains("Ищем", true) || state.contains("нет", true) || state.contains("ошиб", true) || state.contains("загруж", true)
            val rows = nearbyList.childCount.coerceAtMost(if (m.tablet) 4 else 3)
            val targetDp = if (compactState) {
                if (m.compact) 128 else 142
            } else {
                (118 + rows * if (m.compact) 42 else 46).coerceAtMost(if (m.tablet) 286 else 248)
            }
            val navLp = bottomNav.layoutParams as? FrameLayout.LayoutParams
            val navBottom = navLp?.bottomMargin ?: 0
            val side = if (m.tablet) max(m.contentMarginDp, (m.widthDp - 720) / 2) else m.contentMarginDp
            (nearbyPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.height = dp(targetDp)
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                lp.bottomMargin = navBottom + dp(m.navHeightDp + 8)
                nearbyPanel.layoutParams = lp
            }
        }

        private fun applyRouteOptionsGeometry(m: Metrics) {
            val ratio = when {
                m.tablet && m.landscape -> 0.58f
                m.tablet -> 0.52f
                m.compact -> 0.62f
                m.heightDp >= 850 -> 0.56f
                else -> 0.59f
            }
            val targetDp = (m.heightDp * ratio).roundToInt().coerceIn(if (m.compact) 360 else 390, if (m.tablet) 620 else 540)
            val side = if (m.tablet) max(m.contentMarginDp, (m.widthDp - 720) / 2) else m.contentMarginDp
            (routeSheet.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.height = dp(targetDp)
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                lp.bottomMargin = dp(8)
                routeSheet.layoutParams = lp
            }
        }

        private fun applyActiveGeometry(m: Metrics) {
            val top = root.findViewWithTag<View>(ACTIVE_TOP_TAG)
            val mini = root.findViewWithTag<View>(ACTIVE_MINI_TAG)
            val topMargin = (top?.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: dp(12)
            val topHeight = top?.height?.takeIf { it > 0 } ?: dp(if (m.compact) 230 else 250)
            val topBottom = topMargin + topHeight
            val miniLp = mini?.layoutParams as? FrameLayout.LayoutParams
            val miniHeight = mini?.height?.takeIf { it > 0 } ?: dp(if (m.compact) 66 else 72)
            val miniBottom = miniLp?.bottomMargin ?: 0
            val bottomReserved = miniHeight + miniBottom + dp(10)
            val bannerReserved = if (locationActionPanel.visibility == View.VISIBLE) dp(if (m.compact) 106 else 118) else 0
            val availablePx = (m.heightPx - topBottom - bottomReserved - bannerReserved - dp(18)).coerceAtLeast(dp(180))
            val desiredPx = dp(if (m.compact) 286 else if (m.tablet) 380 else 340)
            val height = min(desiredPx, availablePx).coerceAtLeast(dp(if (m.compact) 190 else 220))
            val side = if (m.tablet) max(m.contentMarginDp, (m.widthDp - 720) / 2) else m.contentMarginDp
            (routeSheet.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.height = height
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                lp.bottomMargin = bottomReserved
                routeSheet.layoutParams = lp
            }
        }

        private fun applyLocationActionGeometry(m: Metrics, mode: ScreenMode) {
            if (locationActionPanel.visibility != View.VISIBLE) return
            val side = if (m.tablet) max(m.contentMarginDp, (m.widthDp - 680) / 2) else m.contentMarginDp
            (locationActionPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(side)
                lp.rightMargin = dp(side)
                lp.bottomMargin = when (mode) {
                    ScreenMode.TRIP -> {
                        val sheetLp = routeSheet.layoutParams as? FrameLayout.LayoutParams
                        (sheetLp?.bottomMargin ?: 0) + routeSheet.height.coerceAtLeast(sheetLp?.height ?: 0) + dp(10)
                    }
                    ScreenMode.HOME, ScreenMode.SETTINGS -> {
                        if (nearbyPanel.visibility == View.VISIBLE) {
                            val nearbyLp = nearbyPanel.layoutParams as? FrameLayout.LayoutParams
                            (nearbyLp?.bottomMargin ?: 0) + nearbyPanel.height.coerceAtLeast(nearbyLp?.height ?: 0) + dp(10)
                        } else {
                            val navLp = bottomNav.layoutParams as? FrameLayout.LayoutParams
                            (navLp?.bottomMargin ?: 0) + dp(m.navHeightDp + 10)
                        }
                    }
                    else -> dp(18)
                }
                locationActionPanel.layoutParams = lp
            }
        }

        private fun styleFilters(m: Metrics) {
            for (index in 0 until routeFilterPanel.childCount) {
                (routeFilterPanel.getChildAt(index) as? TextView)?.apply {
                    minimumHeight = dp(if (m.compact) 36 else 40)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = if (m.compact) 10.5f else 11.5f
                    setPadding(dp(if (m.compact) 8 else 10), 0, dp(if (m.compact) 8 else 10), 0)
                }
            }
            routeFilters.layoutParams = routeFilters.layoutParams.apply { height = dp(if (m.compact) 42 else 46) }
        }

        private fun styleRoutePanel(m: Metrics) {
            for (index in 0 until routePanel.childCount) {
                when (val child = routePanel.getChildAt(index)) {
                    is TextView -> {
                        val text = child.text?.toString().orEmpty()
                        if (text.contains("  →  ")) child.visibility = View.GONE
                        if (text == "Варианты маршрута") child.visibility = View.GONE
                    }
                    is LinearLayout -> if (child.isClickable) compactRouteCard(child, m)
                }
            }
        }

        private fun compactRouteCard(card: LinearLayout, m: Metrics) {
            if (card.getTag(R.id.routeFiltersPanel) == true) return
            val top = card.getChildAt(0) as? LinearLayout ?: return
            val badge = card.getChildAt(1) as? TextView
            val chain = card.getChildAt(2) as? TextView
            val meta = card.getChildAt(3) as? TextView
            val keep = listOfNotNull<View>(top, badge, chain, meta).toSet()
            for (index in 0 until card.childCount) {
                val child = card.getChildAt(index)
                if (child !in keep) child.visibility = View.GONE
            }
            if (badge != null && badge.parent === card) {
                card.removeView(badge)
                top.addView(badge, 1.coerceAtMost(top.childCount), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(7)
                })
            }
            card.apply {
                elevation = dp(3).toFloat()
                background = rounded(color(R.color.vh_surface_solid), if (m.compact) 18f else 21f, color(R.color.vh_border), 0.6f)
                setPadding(dp(if (m.compact) 9 else 11), dp(6), dp(if (m.compact) 9 else 11), dp(6))
                (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.topMargin = dp(if (m.compact) 4 else 6)
                    layoutParams = lp
                }
            }
            (top.getChildAt(0) as? TextView)?.apply {
                textSize = if (m.compact) 18f else 20f
                includeFontPadding = false
                maxLines = 1
            }
            badge?.apply {
                textSize = if (m.compact) 8f else 9f
                maxLines = 1
                includeFontPadding = false
                setPadding(dp(5), dp(1), dp(5), dp(1))
            }
            (top.getChildAt(top.childCount - 1) as? TextView)?.apply {
                textSize = if (m.compact) 9.5f else 10.5f
                maxLines = 1
                includeFontPadding = false
            }
            chain?.apply {
                maxLines = if (m.tablet) 2 else 1
                ellipsize = TextUtils.TruncateAt.END
                textSize = if (m.compact) 10f else 10.5f
                setPadding(0, dp(2), 0, 0)
            }
            meta?.apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textSize = if (m.compact) 9f else 9.5f
                setPadding(0, dp(1), 0, 0)
            }
            card.setTag(R.id.routeFiltersPanel, true)
        }

        private fun styleNearbyRow(view: View, m: Metrics) {
            val row = view as? LinearLayout ?: return
            row.minimumHeight = dp(if (m.compact) 46 else 50)
            (row.getChildAt(0) as? TextView)?.apply {
                val size = if (m.compact) 42 else 46
                val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(dp(size), dp(size))
                lp.width = dp(size)
                lp.height = dp(size)
                layoutParams = lp
                gravity = Gravity.CENTER
                maxLines = 1
                textSize = if (m.compact) 10.5f else 11.5f
                setTypeface(typeface, Typeface.BOLD)
                background = rounded(color(R.color.vh_primary_soft), 13f)
            }
        }

        private fun ensureEndpointHeader(m: Metrics) {
            val route = currentRoute() ?: return
            val existing = routeSheet.findViewWithTag<View>(ENDPOINTS_TAG)
            if (existing != null && endpointRouteId == route.id) {
                updateEndpointTexts(existing as ViewGroup)
                return
            }
            removeEndpointHeader()
            endpointRouteId = route.id
            val card = LinearLayout(activity).apply {
                tag = ENDPOINTS_TAG
                orientation = LinearLayout.VERTICAL
                setPadding(dp(3), dp(2), dp(3), dp(2))
            }
            val rowHeight = if (m.compact) 30 else 34
            card.addView(endpointRow(displayOrigin(route), color(R.color.vh_success), m), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowHeight)))
            card.addView(endpointRow(displayDestination(route), color(R.color.vh_mcc), m), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowHeight)).apply { topMargin = dp(1) })
            routeSheet.addView(card, 1.coerceAtMost(routeSheet.childCount), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowHeight * 2 + 4)))
        }

        private fun endpointRow(value: String, dotColor: Int, m: Metrics): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.vh_surface_solid))
                    setStroke(dp(3), dotColor)
                }
            }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(10) })
            addView(TextView(activity).apply {
                text = value
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textSize = if (m.compact) 15.5f else 17f
                includeFontPadding = false
                setTextColor(color(R.color.vh_text_primary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        private fun updateEndpointTexts(card: ViewGroup) {
            val labels = descendants(card).filterIsInstance<TextView>().toList()
            val route = currentRoute() ?: return
            if (labels.isNotEmpty()) labels[0].text = displayOrigin(route)
            if (labels.size > 1) labels[1].text = displayDestination(route)
        }

        private fun displayOrigin(route: RouteCandidate): String = fromField.text?.toString().orEmpty().substringBefore(',').trim().ifBlank { route.legs.first().from.name }
        private fun displayDestination(route: RouteCandidate): String = toField.text?.toString().orEmpty().substringBefore(',').trim().ifBlank { route.legs.last().to.name }

        private fun removeEndpointHeader() {
            routeSheet.findViewWithTag<View>(ENDPOINTS_TAG)?.let(routeSheet::removeView)
            endpointRouteId = null
        }

        private fun ensureActiveChrome(m: Metrics) {
            val route = currentRoute() ?: return
            val existing = root.findViewWithTag<View>(ACTIVE_TOP_TAG)
            if (activeRouteId == route.id && existing != null) return
            removeActiveChrome()
            activeRouteId = route.id
            val leg = currentTransitLeg(route)
            val now = Instant.now().epochSecond
            val stops = leg.stopCount.coerceAtLeast(3).coerceAtMost(7)
            val remainingStops = remainingStops(leg, now)
            val minutes = max(1, ceil((leg.arrivalEpochSec - now).coerceAtLeast(0L) / 60.0).toInt())
            val rawLine = leg.lineName?.takeIf(String::isNotBlank) ?: modeLabel(leg.mode)
            val badge = shortBadge(rawLine, leg.mode)

            val top = LinearLayout(activity).apply {
                tag = ACTIVE_TOP_TAG
                orientation = LinearLayout.VERTICAL
                elevation = dp(28).toFloat()
                background = rounded(color(R.color.vh_surface_solid), if (m.compact) 25f else 30f, color(R.color.vh_border), 0.5f)
                setPadding(dp(if (m.compact) 14 else 18), dp(if (m.compact) 12 else 15), dp(if (m.compact) 14 else 18), dp(if (m.compact) 12 else 15))
                addView(activeHeader(badge, leg, m))
                addView(activeStationRow(leg, m))
                addView(ReferenceTripProgressViewV2(activity).apply {
                    progressFraction = tripProgress(leg, now)
                    stopCount = stops
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (m.compact) 32 else 36)))
                addView(activeExitRow(leg, remainingStops, minutes, m))
            }
            val topMargin = (searchPanel.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: dp(12)
            val side = if (m.tablet) max(m.contentMarginDp, (m.widthDp - 720) / 2) else m.contentMarginDp
            root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                leftMargin = dp(side)
                rightMargin = dp(side)
                this.topMargin = topMargin
            })

            val miniHeight = if (m.compact) 66 else 72
            val mini = LinearLayout(activity).apply {
                tag = ACTIVE_MINI_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                elevation = dp(28).toFloat()
                background = rounded(color(R.color.vh_surface_solid), miniHeight / 2f, color(R.color.vh_border), 0.5f)
                setPadding(dp(10), dp(8), dp(12), dp(8))
                addView(lineBadge(badge, leg.mode, true, m))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = "В пути до ${leg.to.name}"
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        textSize = if (m.compact) 13.5f else 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(R.color.vh_text_primary))
                    })
                    addView(TextView(activity).apply {
                        text = if (remainingStops > 0) "Выходите через $remainingStops ${stopWord(remainingStops)}" else "Следующая — выход"
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        textSize = if (m.compact) 10.5f else 12f
                        setTextColor(color(R.color.vh_text_tertiary))
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
                addView(TextView(activity).apply {
                    text = "$minutes мин"
                    gravity = Gravity.CENTER
                    maxLines = 1
                    textSize = if (m.compact) 12.5f else 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_success))
                    background = rounded(Color.argb(28, 18, 183, 106), 14f)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                })
            }
            val navBottom = (bottomNav.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: 0
            root.addView(mini, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(miniHeight)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(side)
                rightMargin = dp(side)
                bottomMargin = navBottom + dp(4)
            })
            top.alpha = 0f
            top.translationY = -dp(10).toFloat()
            top.animate().alpha(1f).translationY(0f).setDuration(200L).start()
            mini.alpha = 0f
            mini.translationY = dp(10).toFloat()
            mini.animate().alpha(1f).translationY(0f).setDuration(200L).start()
            top.post { applyActiveGeometry(metrics()) }
        }

        private fun activeHeader(badge: String, leg: RouteLeg, m: Metrics): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lineBadge(badge, leg.mode, false, m))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = "В пути"
                    textSize = if (m.compact) 20f else 22f
                    includeFontPadding = false
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_text_primary))
                })
                addView(TextView(activity).apply {
                    text = "В направлении ${leg.to.name}"
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = if (m.compact) 12.5f else 14f
                    includeFontPadding = false
                    setTextColor(color(R.color.vh_text_secondary))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(if (m.compact) 10 else 14) })
            addView(TextView(activity).apply {
                text = "⌄"
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(color(R.color.vh_text_secondary))
            }, LinearLayout.LayoutParams(dp(34), dp(40)))
        }

        private fun lineBadge(label: String, mode: TransportMode, compact: Boolean, m: Metrics): TextView = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = if (compact) if (m.compact) 12.5f else 13.5f else if (m.compact) 14f else 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(modeColor(mode), if (compact) 13f else 15f)
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconForMode(mode), 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            compoundDrawablePadding = dp(4)
            val width = if (compact) if (m.compact) 64 else 72 else if (m.compact) 74 else 84
            val height = if (compact) if (m.compact) 46 else 50 else if (m.compact) 52 else 56
            layoutParams = LinearLayout.LayoutParams(dp(width), dp(height))
            setPadding(dp(5), 0, dp(5), 0)
        }

        private fun shortBadge(raw: String, mode: TransportMode): String {
            val clean = raw.replace(Regex("\\s+"), " ").trim()
            val number = Regex("[A-Za-zА-Яа-я]?[0-9]{1,3}").find(clean)?.value
            return when (mode) {
                TransportMode.MCC -> number?.let { "МЦК ${it.filter(Char::isDigit)}" } ?: "МЦК"
                TransportMode.MCD -> number ?: clean.substringBefore('·').take(5)
                TransportMode.METRO -> number ?: clean.substringBefore('·').take(4)
                TransportMode.BUS, TransportMode.TRAM -> clean.substringBefore('·').substringBefore(' ').take(5).ifBlank { modeLabel(mode) }
                TransportMode.TRAIN -> number ?: "Поезд"
                TransportMode.WALK -> "Пешком"
            }
        }

        private fun activeStationRow(leg: RouteLeg, m: Metrics): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (m.compact) 9 else 12), 0, dp(2))
            addView(stationLabel(leg.from.name, Gravity.START, m), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "Сейчас"
                gravity = Gravity.CENTER
                textSize = if (m.compact) 11.5f else 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.vh_primary))
            }, LinearLayout.LayoutParams(dp(if (m.compact) 62 else 72), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(stationLabel(leg.to.name, Gravity.END, m), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        private fun stationLabel(value: String, gravityValue: Int, m: Metrics) = TextView(activity).apply {
            text = value
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = gravityValue
            textSize = if (m.compact) 11.5f else 12.5f
            setTextColor(color(R.color.vh_text_secondary))
        }

        private fun activeExitRow(leg: RouteLeg, remainingStops: Int, minutes: Int, m: Metrics): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (m.compact) 5 else 8), 0, 0)
            addView(TextView(activity).apply {
                text = "↳"
                gravity = Gravity.CENTER
                textSize = if (m.compact) 19f else 21f
                setTextColor(color(R.color.vh_success))
                background = rounded(color(R.color.vh_surface_muted), 13f)
            }, LinearLayout.LayoutParams(dp(if (m.compact) 42 else 46), dp(if (m.compact) 42 else 46)).apply { rightMargin = dp(9) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = if (remainingStops > 0) "Выходите через $remainingStops ${stopWord(remainingStops)}" else "Готовьтесь к выходу"
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = if (m.compact) 14f else 15.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_text_primary))
                })
                addView(TextView(activity).apply {
                    text = leg.to.name
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = if (m.compact) 11f else 12.5f
                    setTextColor(color(R.color.vh_text_tertiary))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "$minutes мин\nдо пересадки"
                gravity = Gravity.CENTER
                textSize = if (m.compact) 11.5f else 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.vh_success))
                background = rounded(Color.argb(28, 18, 183, 106), 15f)
                setPadding(dp(if (m.compact) 8 else 11), dp(6), dp(if (m.compact) 8 else 11), dp(6))
            })
        }

        private fun clearLegacyActiveHeader() {
            descendants(routePanel).filterIsInstance<TextView>().filter { it.text?.toString() == "В пути" }.forEach {
                it.text = ""
                it.contentDescription = null
                it.visibility = View.GONE
            }
        }

        private fun enforceUnifiedStripVisibility(active: Boolean) {
            descendants(routePanel).filterIsInstance<HorizontalScrollView>().forEach { scroll ->
                when (scroll.contentDescription?.toString().orEmpty()) {
                    TransitJourneyVisibilityGuard.V2_DESCRIPTION -> scroll.visibility = if (active) View.VISIBLE else View.GONE
                    "Схема транспорта маршрута" -> scroll.visibility = View.GONE
                }
            }
        }

        private fun removeActiveChrome() {
            root.findViewWithTag<View>(ACTIVE_TOP_TAG)?.let(root::removeView)
            root.findViewWithTag<View>(ACTIVE_MINI_TAG)?.let(root::removeView)
            activeRouteId = null
        }

        private fun animateModeEntrance(mode: ScreenMode) {
            val target = when (mode) {
                ScreenMode.HOME -> nearbyPanel.takeIf { it.visibility == View.VISIBLE }
                ScreenMode.ROUTES, ScreenMode.TRIP -> routeSheet.takeIf { it.visibility == View.VISIBLE }
                ScreenMode.SEARCH -> searchPanel.takeIf { it.visibility == View.VISIBLE }
                ScreenMode.SETTINGS -> settingsPanel.parent as? View
            } ?: return
            if (animated[target] == true && mode == lastMode) return
            animated[target] = true
            target.animate().cancel()
            target.alpha = 0f
            target.translationY = if (mode == ScreenMode.SETTINGS) 0f else dp(10).toFloat()
            target.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        }

        private fun currentRoute(): RouteCandidate? =
            TripLiveState.current()?.route ?: ActiveTripStore.load(activity)?.route ?: LastPlanStore.seed?.route

        private fun currentTransitLeg(route: RouteCandidate): RouteLeg {
            val now = Instant.now().epochSecond
            return route.legs.firstOrNull { it.mode != TransportMode.WALK && now < it.arrivalEpochSec }
                ?: route.legs.firstOrNull { it.mode != TransportMode.WALK }
                ?: route.legs.first()
        }

        private fun isActiveTrip(): Boolean = routeSheet.visibility == View.VISIBLE &&
            routePrimaryAction.visibility == View.VISIBLE &&
            routePrimaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true

        private fun remainingStops(leg: RouteLeg, now: Long): Int {
            if (leg.stopCount <= 0) return 0
            val fraction = tripProgress(leg, now)
            return (leg.stopCount - (leg.stopCount * fraction).toInt()).coerceAtLeast(0)
        }

        private fun tripProgress(leg: RouteLeg, now: Long): Float {
            val duration = (leg.arrivalEpochSec - leg.departureEpochSec).coerceAtLeast(1L)
            return ((now - leg.departureEpochSec).toDouble() / duration.toDouble()).coerceIn(0.08, 0.92).toFloat()
        }

        private fun modeLabel(mode: TransportMode): String = when (mode) {
            TransportMode.WALK -> "Пешком"
            TransportMode.BUS -> "Автобус"
            TransportMode.TRAM -> "Трамвай"
            TransportMode.METRO -> "Метро"
            TransportMode.MCC -> "МЦК"
            TransportMode.MCD -> "МЦД"
            TransportMode.TRAIN -> "Поезд"
        }

        private fun modeColor(mode: TransportMode): Int = color(when (mode) {
            TransportMode.WALK -> R.color.vh_text_secondary
            TransportMode.BUS -> R.color.vh_bus
            TransportMode.TRAM -> R.color.vh_tram
            TransportMode.METRO -> R.color.vh_metro
            TransportMode.MCC -> R.color.vh_mcc
            TransportMode.MCD -> R.color.vh_mcd
            TransportMode.TRAIN -> R.color.vh_train
        })

        private fun iconForMode(mode: TransportMode): Int = when (mode) {
            TransportMode.BUS -> R.drawable.ic_bus
            TransportMode.TRAM -> R.drawable.ic_tram
            TransportMode.METRO, TransportMode.MCC, TransportMode.MCD -> R.drawable.ic_metro
            TransportMode.TRAIN -> R.drawable.ic_transport
            TransportMode.WALK -> R.drawable.ic_routes
        }

        private fun stopWord(count: Int): String = when {
            count % 10 == 1 && count % 100 != 11 -> "остановку"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "остановки"
            else -> "остановок"
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }

        private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 0f): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null && strokeDp > 0f) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
        }

        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        private fun dp(value: Float): Int = (value * density + 0.5f).toInt()
    }

    private const val ENDPOINTS_TAG = "reference_route_endpoints"
    private const val ACTIVE_TOP_TAG = "reference_active_trip_top"
    private const val ACTIVE_MINI_TAG = "reference_active_trip_mini"
}

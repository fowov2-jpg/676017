package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.TransportMode
import java.time.Instant
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Finite post-layout tuning for the approved reference proportions. */
internal object ReferenceVisualTuning {
    private val installed = WeakHashMap<MainActivity, Boolean>()
    private val nearbyObserversInstalled = WeakHashMap<MainActivity, Boolean>()
    private val routeObserversInstalled = WeakHashMap<MainActivity, Boolean>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (installed.put(activity, true) == true) return
        installNearbyObservers(activity)
        installRouteObserver(activity)
        tune(activity)
        activity.window.decorView.postDelayed({ tune(activity) }, 220L)
        activity.window.decorView.postDelayed({ tune(activity) }, 720L)
        activity.window.decorView.postDelayed({ tune(activity) }, 1_500L)
        activity.window.decorView.postDelayed({ tune(activity) }, 3_000L)
    }

    private fun tune(activity: MainActivity) {
        tuneSettingsSheet(activity)
        tuneNearbyPanel(activity)
        tuneRouteChrome(activity)
        tuneRouteCards(activity)
        tuneActiveTripBadge(activity)
    }

    private fun installNearbyObservers(activity: MainActivity) {
        if (nearbyObserversInstalled.put(activity, true) == true) return
        val panel = activity.findViewById<View>(R.id.nearbyPanel)
        val list = activity.findViewById<ViewGroup>(R.id.nearbyList)
        val state = activity.findViewById<TextView>(R.id.nearbyStateText)

        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            panel.post { tuneNearbyPanel(activity) }
        }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            panel.post { tuneNearbyPanel(activity) }
        }
        state.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                panel.post { tuneNearbyPanel(activity) }
            }
        })
    }

    private fun installRouteObserver(activity: MainActivity) {
        if (routeObserversInstalled.put(activity, true) == true) return
        val panel = activity.findViewById<View>(R.id.routeResultsPanel)
        val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            panel.post {
                tuneRouteChrome(activity)
                tuneRouteCards(activity)
            }
        }
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            sheet.post { tuneRouteChrome(activity) }
        }
    }

    private fun tuneNearbyPanel(activity: MainActivity) {
        val panel = activity.findViewById<View>(R.id.nearbyPanel)
        val list = activity.findViewById<ViewGroup>(R.id.nearbyList)
        val state = activity.findViewById<TextView>(R.id.nearbyStateText).text?.toString().orEmpty()

        val targetRowHeight = dp(activity, 50)
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            if (row.minimumHeight != targetRowHeight) row.minimumHeight = targetRowHeight
        }

        val compactState = list.childCount == 0 ||
            state.contains("Ищем", ignoreCase = true) ||
            state.contains("нет", ignoreCase = true) ||
            state.contains("ошиб", ignoreCase = true)
        val targetDp = if (compactState) {
            158
        } else {
            (158 + list.childCount.coerceAtMost(3) * 10).coerceAtMost(188)
        }
        val targetHeight = dp(activity, targetDp)
        val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.height != targetHeight) {
            params.height = targetHeight
            panel.layoutParams = params
        }
    }

    private fun tuneSettingsSheet(activity: MainActivity) {
        val settingsPanel = activity.findViewById<ViewGroup>(R.id.settingsPanel)
        val scroll = settingsPanel.parent as? ScrollView ?: return
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val targetWidth = (screenWidth * 0.47f).roundToInt()
        val params = scroll.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != targetWidth || params.gravity != Gravity.END) {
            params.width = targetWidth
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.END
            params.leftMargin = 0
            scroll.layoutParams = params
        }
        if (settingsPanel.paddingLeft != dp(activity, 12) || settingsPanel.paddingRight != dp(activity, 10)) {
            settingsPanel.setPadding(dp(activity, 12), settingsPanel.paddingTop, dp(activity, 10), settingsPanel.paddingBottom)
        }

        val header = settingsPanel.getChildAt(0) as? ViewGroup
        val title = header?.getChildAt(0) as? TextView
        title?.apply {
            val targetPx = 19f * activity.resources.displayMetrics.scaledDensity
            if (kotlin.math.abs(textSize - targetPx) > 0.5f) {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f)
            }
            if (maxLines != 1) maxLines = 1
            if (ellipsize != TextUtils.TruncateAt.END) ellipsize = TextUtils.TruncateAt.END
            if (includeFontPadding) includeFontPadding = false
        }
        activity.findViewById<TextView>(R.id.closeSettingsButton).apply {
            val targetPx = 26f * activity.resources.displayMetrics.scaledDensity
            if (kotlin.math.abs(textSize - targetPx) > 0.5f) {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
            }
            val lp = layoutParams
            val target = dp(activity, 40)
            if (lp.width != target || lp.height != target) {
                lp.width = target
                lp.height = target
                layoutParams = lp
            }
        }

        val sectionNames = setOf(
            activity.getString(R.string.settings_map),
            activity.getString(R.string.settings_routes)
        )
        for (index in 0 until settingsPanel.childCount) {
            val child = settingsPanel.getChildAt(index)
            if (child is TextView && child.text?.toString() in sectionNames && child.visibility != View.GONE) {
                child.visibility = View.GONE
            }
        }

        val switches = mapOf(
            R.id.showStopsSwitch to ("Показывать остановки" to "Отображать остановки общественного транспорта на карте"),
            R.id.showTransportSwitch to ("Показывать линии маршрута" to "Показывать транспортные сегменты выбранного маршрута"),
            R.id.darkThemeSwitch to ("Тёмная тема" to "Использовать тёмную тему интерфейса и карты"),
            R.id.lessWalkingSwitch to ("Меньше ходьбы" to "Предпочитать варианты с минимальной ходьбой"),
            R.id.avoidTransfersSwitch to ("Избегать пересадок" to "Предпочитать маршруты с меньшим числом пересадок")
        )
        switches.forEach { (id, copy) ->
            activity.findViewById<SwitchCompat>(id).apply {
                if (text?.toString() != copy.first) text = copy.first
                val description = "${copy.first}. ${copy.second}"
                if (contentDescription?.toString() != description) contentDescription = description
                val targetTextPx = 11.5f * activity.resources.displayMetrics.scaledDensity
                if (kotlin.math.abs(textSize - targetTextPx) > 0.5f) {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
                }
                if (maxLines != 2) maxLines = 2
                if (minimumHeight != dp(activity, 62)) minimumHeight = dp(activity, 62)
                setLineSpacing(0f, 1f)
                val vertical = dp(activity, 4)
                if (paddingTop != vertical || paddingBottom != vertical) {
                    setPadding(0, vertical, 0, vertical)
                }
            }
        }

        descendants(settingsPanel)
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString()?.contains("Live-позиции", ignoreCase = true) == true }
            ?.apply {
                val targetPx = 9.5f * activity.resources.displayMetrics.scaledDensity
                if (kotlin.math.abs(textSize - targetPx) > 0.5f) {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9.5f)
                }
                if (maxLines != 4) maxLines = 4
            }
    }

    private fun isRouteOptions(activity: MainActivity): Boolean {
        val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        val filters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        val primaryAction = activity.findViewById<Button>(R.id.routePrimaryAction)
        val activeTrip = primaryAction.visibility == View.VISIBLE &&
            primaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true
        return sheet.visibility == View.VISIBLE && filters.visibility == View.VISIBLE && !activeTrip
    }

    private fun tuneRouteChrome(activity: MainActivity) {
        if (!isRouteOptions(activity)) return
        hideIfVisible(activity.findViewById(R.id.searchPanel))
        hideIfVisible(activity.findViewById(R.id.quickActions))
        hideIfVisible(activity.findViewById(R.id.locationButton))
        hideIfVisible(activity.findViewById(R.id.settingsButton))
        hideIfVisible(activity.findViewById(R.id.bottomNav))

        val root = activity.findViewById<FrameLayout>(R.id.root)
        val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        val rootHeight = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val targetHeight = (rootHeight * 0.56f).roundToInt()
            .coerceIn(dp(activity, 390), dp(activity, 520))
        (sheet.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.height != targetHeight) {
                lp.height = targetHeight
                sheet.layoutParams = lp
            }
        }

        val panel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        for (index in 0 until panel.childCount) {
            val child = panel.getChildAt(index)
            if (child is HorizontalScrollView &&
                child.contentDescription?.toString() == TransitJourneyVisibilityGuard.V2_DESCRIPTION &&
                child.visibility != View.GONE
            ) {
                child.visibility = View.GONE
            }
        }
    }

    private fun tuneRouteCards(activity: MainActivity) {
        if (!isRouteOptions(activity)) return
        val panel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        val primaryAction = activity.findViewById<Button>(R.id.routePrimaryAction)

        primaryAction.apply {
            val targetHeight = dp(activity, 44)
            val lp = layoutParams
            if (lp.height != targetHeight) {
                lp.height = targetHeight
                layoutParams = lp
            }
            if (minimumHeight != targetHeight) minimumHeight = targetHeight
            val targetPx = 13.5f * activity.resources.displayMetrics.scaledDensity
            if (kotlin.math.abs(textSize - targetPx) > 0.5f) {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            }
        }

        for (index in 0 until panel.childCount) {
            when (val child = panel.getChildAt(index)) {
                is TextView -> {
                    if (child.text?.toString() == "Варианты маршрута" && child.visibility != View.GONE) {
                        child.visibility = View.GONE
                    }
                }
                is LinearLayout -> if (child.isClickable && child.getTag(R.id.routeFiltersPanel) != true) {
                    compactRouteCard(activity, child)
                    child.setTag(R.id.routeFiltersPanel, true)
                }
            }
        }
    }

    private fun compactRouteCard(activity: MainActivity, card: LinearLayout) {
        val top = card.getChildAt(0) as? LinearLayout ?: return
        val badge = card.getChildAt(1) as? TextView
        val chain = card.getChildAt(2) as? TextView
        val meta = card.getChildAt(3) as? TextView
        val keep = setOf<View>(top, badge, chain, meta)

        for (index in 0 until card.childCount) {
            val child = card.getChildAt(index)
            if (child !in keep) child.visibility = View.GONE
        }

        if (badge != null && badge.parent === card) {
            card.removeView(badge)
            top.addView(
                badge,
                1.coerceAtMost(top.childCount),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(activity, 8) }
            )
        }

        card.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6))
        (card.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = dp(activity, 5)
            card.layoutParams = lp
        }

        (top.getChildAt(0) as? TextView)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f)
            includeFontPadding = false
        }
        badge?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8.5f)
            setPadding(dp(activity, 6), dp(activity, 1), dp(activity, 6), dp(activity, 1))
            includeFontPadding = false
            maxLines = 1
        }
        (top.getChildAt(top.childCount - 1) as? TextView)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
            includeFontPadding = false
        }
        chain?.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(0, dp(activity, 3), 0, 0)
        }
        meta?.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setPadding(0, dp(activity, 2), 0, 0)
        }
    }

    private fun hideIfVisible(view: View) {
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun tuneActiveTripBadge(activity: MainActivity) {
        val root = activity.findViewById<FrameLayout>(R.id.root)
        val top = root.findViewWithTag<ViewGroup>("reference_active_trip_top") ?: return
        if (top.getTag(R.id.routePrimaryAction) == true) return
        val route = LastPlanStore.seed?.route ?: return
        val now = Instant.now().epochSecond
        val leg = route.legs.firstOrNull { it.mode != TransportMode.WALK && now < it.arrivalEpochSec }
            ?: route.legs.firstOrNull { it.mode != TransportMode.WALK }
            ?: return
        val line = leg.lineName?.takeIf(String::isNotBlank) ?: return
        descendants(top)
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == line }
            ?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconForMode(leg.mode), 0, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                compoundDrawablePadding = dp(activity, 5)
                top.setTag(R.id.routePrimaryAction, true)
            }
    }

    private fun iconForMode(mode: TransportMode): Int = when (mode) {
        TransportMode.BUS -> R.drawable.ic_bus
        TransportMode.TRAM -> R.drawable.ic_tram
        TransportMode.METRO, TransportMode.MCC, TransportMode.MCD -> R.drawable.ic_metro
        TransportMode.TRAIN -> R.drawable.ic_transport
        TransportMode.WALK -> R.drawable.ic_routes
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}

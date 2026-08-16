package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    @Synchronized
    fun install(activity: MainActivity) {
        if (installed.put(activity, true) == true) return
        tune(activity)
        activity.window.decorView.postDelayed({ tune(activity) }, 220L)
        activity.window.decorView.postDelayed({ tune(activity) }, 720L)
        activity.window.decorView.postDelayed({ tune(activity) }, 1_500L)
        activity.window.decorView.postDelayed({ tune(activity) }, 3_000L)
    }

    private fun tune(activity: MainActivity) {
        tuneSettingsSheet(activity)
        tuneRouteChrome(activity)
        tuneActiveTripBadge(activity)
    }

    private fun tuneSettingsSheet(activity: MainActivity) {
        val settingsPanel = activity.findViewById<ViewGroup>(R.id.settingsPanel)
        val scroll = settingsPanel.parent as? ScrollView ?: return
        val width = activity.resources.displayMetrics.widthPixels
        val targetWidth = (width * 0.42f).roundToInt()
        val params = scroll.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != targetWidth || params.gravity != Gravity.END) {
            params.width = targetWidth
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.END
            params.leftMargin = 0
            scroll.layoutParams = params
        }
        if (settingsPanel.paddingLeft != dp(activity, 18) || settingsPanel.paddingRight != dp(activity, 16)) {
            settingsPanel.setPadding(dp(activity, 18), settingsPanel.paddingTop, dp(activity, 16), settingsPanel.paddingBottom)
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
                contentDescription = "${copy.first}. ${copy.second}"
                val targetTextPx = 12.5f * activity.resources.displayMetrics.scaledDensity
                if (kotlin.math.abs(textSize - targetTextPx) > 0.5f) setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
                if (minimumHeight != dp(activity, 72)) minimumHeight = dp(activity, 72)
                setLineSpacing(0f, 1f)
            }
        }
    }

    private fun tuneRouteChrome(activity: MainActivity) {
        val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        val filters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        val primaryAction = activity.findViewById<Button>(R.id.routePrimaryAction)
        val activeTrip = primaryAction.visibility == View.VISIBLE &&
            primaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true
        val routeOptions = sheet.visibility == View.VISIBLE && filters.visibility == View.VISIBLE && !activeTrip
        if (!routeOptions) return

        hideIfVisible(activity.findViewById(R.id.searchPanel))
        hideIfVisible(activity.findViewById(R.id.quickActions))
        hideIfVisible(activity.findViewById(R.id.locationButton))
        hideIfVisible(activity.findViewById(R.id.settingsButton))
        hideIfVisible(activity.findViewById(R.id.bottomNav))
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

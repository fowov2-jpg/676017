package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import java.util.WeakHashMap

/**
 * Keeps the detailed active-trip sheet visually consistent with the passenger-GPS state.
 *
 * ResponsiveProductUi remains the sole geometry owner. This binder only changes copy, progress,
 * tint and transport pictograms inside views that already exist.
 */
internal object TripProgressDetailBinder {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        controllers.getOrPut(activity) { Controller(activity) }.resume()
    }

    @Synchronized
    fun pause(activity: MainActivity) {
        controllers[activity]?.pause()
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) {
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val routePanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private val routeSheet = activity.findViewById<View>(R.id.routeResultsContainer)
        private val routePrimaryAction = activity.findViewById<TextView>(R.id.routePrimaryAction)
        private var resumed = false

        private val listener: (TripProgressSnapshot) -> Unit = { snapshot ->
            if (resumed) activity.runOnUiThread { render(snapshot) }
        }

        init {
            TripProgressState.addListener(listener)
        }

        fun resume() {
            resumed = true
            TripProgressState.current()?.let(::render)
        }

        fun pause() {
            resumed = false
        }

        fun destroy() {
            resumed = false
            TripProgressState.removeListener(listener)
        }

        private fun render(snapshot: TripProgressSnapshot) {
            if (activity.isFinishing || activity.isDestroyed || !isActiveTrip()) return
            val route = currentRoute()?.takeIf { it.id == snapshot.routeId } ?: return
            val leg = route.legs.getOrNull(snapshot.legIndex) ?: return
            updateTransportIcons(leg.mode)
            updateCurrentStageCard(route, leg, snapshot)
        }

        private fun updateTransportIcons(mode: TransportMode) {
            val icon = iconForMode(mode)
            val tint = ColorStateList.valueOf(Color.WHITE)
            val top = root.findViewWithTag<ViewGroup>(ACTIVE_TOP_TAG)
            val header = top?.childAtOrNull(0) as? ViewGroup
            (header?.childAtOrNull(0) as? TextView)?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawableTintList = tint
            }
            val mini = root.findViewWithTag<ViewGroup>(ACTIVE_MINI_TAG)
            (mini?.childAtOrNull(0) as? TextView)?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawableTintList = tint
            }
        }

        private fun updateCurrentStageCard(
            route: RouteCandidate,
            leg: RouteLeg,
            snapshot: TripProgressSnapshot
        ) {
            val card = currentStageCard() ?: return
            card.tag = DETAIL_CARD_TAG
            card.contentDescription = "Текущий этап по GPS. ${detailTitle(route, leg, snapshot)}. ${detailInstruction(route, leg, snapshot)}"

            (card.childAtOrNull(0) as? TextView)?.apply {
                text = "Текущий этап по GPS"
                setTextColor(color(R.color.vh_text_tertiary))
            }
            (card.childAtOrNull(1) as? TextView)?.apply {
                text = detailTitle(route, leg, snapshot)
                setTextColor(modeColor(leg.mode))
                setTypeface(typeface, Typeface.BOLD)
            }
            (card.childAtOrNull(2) as? TextView)?.apply {
                text = "${leg.from.name} → ${leg.to.name}"
                setTextColor(color(R.color.vh_text_secondary))
            }
            (card.childAtOrNull(3) as? TextView)?.apply {
                text = detailInstruction(route, leg, snapshot)
                setTextColor(color(R.color.vh_text_primary))
                setTypeface(typeface, Typeface.BOLD)
            }
            (card.childAtOrNull(4) as? ProgressBar)?.apply {
                max = route.legs.size.coerceAtLeast(1)
                progress = (snapshot.legIndex + 1).coerceAtMost(max)
                progressTintList = ColorStateList.valueOf(color(R.color.vh_primary))
            }
            (card.childAtOrNull(5) as? TextView)?.apply {
                text = "Этап ${snapshot.legIndex + 1} из ${route.legs.size} · GPS ±${snapshot.accuracyMeters.toInt().coerceAtLeast(1)} м"
                setTextColor(color(R.color.vh_text_tertiary))
            }
        }

        /** The active-current card is the direct route-panel child that owns its ProgressBar. */
        private fun currentStageCard(): LinearLayout? {
            routePanel.findViewWithTag<LinearLayout>(DETAIL_CARD_TAG)?.let { return it }
            for (index in 0 until routePanel.childCount) {
                val child = routePanel.getChildAt(index) as? LinearLayout ?: continue
                if ((0 until child.childCount).any { child.getChildAt(it) is ProgressBar }) return child
            }
            return null
        }

        private fun detailTitle(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot): String = when (snapshot.phase) {
            TripProgressPhase.APPROACH -> "Пешком к остановке"
            TripProgressPhase.WAITING -> "Посадка · ${lineLabel(leg)}"
            TripProgressPhase.ONBOARD -> lineLabel(leg)
            TripProgressPhase.ALIGHTING -> "Скоро выход · ${lineLabel(leg)}"
            TripProgressPhase.TRANSFER -> nextTransit(route, snapshot)?.let { "Переход к ${lineLabel(it)}" } ?: "Переход"
            TripProgressPhase.FINAL_WALK -> "Пешком до места"
            TripProgressPhase.FINISHED -> "Вы прибыли"
            TripProgressPhase.OFF_ROUTE -> "Уточняем положение"
        }

        private fun detailInstruction(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot): String = when (snapshot.phase) {
            TripProgressPhase.APPROACH -> distanceInstruction("До остановки", snapshot.distanceRemainingMeters)
            TripProgressPhase.WAITING -> "Ожидайте посадку на ${lineLabel(leg)}"
            TripProgressPhase.ONBOARD -> if (snapshot.remainingStops > 0) {
                "До выхода ${snapshot.remainingStops} ${stopWord(snapshot.remainingStops)}"
            } else "Следующая — выход"
            TripProgressPhase.ALIGHTING -> if (snapshot.remainingStops <= 1) "Выходите на следующей" else "Выход через ${snapshot.remainingStops} ${stopWord(snapshot.remainingStops)}"
            TripProgressPhase.TRANSFER -> nextTransit(route, snapshot)?.let { "Далее: ${lineLabel(it)} · ${it.from.name}" } ?: "Продолжайте переход"
            TripProgressPhase.FINAL_WALK -> distanceInstruction("До места", snapshot.distanceRemainingMeters)
            TripProgressPhase.FINISHED -> "Маршрут завершён"
            TripProgressPhase.OFF_ROUTE -> "GPS сверяет положение с маршрутом"
        }

        private fun nextTransit(route: RouteCandidate, snapshot: TripProgressSnapshot): RouteLeg? =
            route.legs.drop(snapshot.legIndex + 1).firstOrNull { it.mode != TransportMode.WALK }

        private fun lineLabel(leg: RouteLeg): String {
            val mode = when (leg.mode) {
                TransportMode.BUS -> "Автобус"
                TransportMode.TRAM -> "Трамвай"
                TransportMode.METRO -> "Метро"
                TransportMode.MCC -> "МЦК"
                TransportMode.MCD -> "МЦД"
                TransportMode.TRAIN -> "Поезд"
                TransportMode.WALK -> "Пешком"
            }
            val raw = leg.lineName?.takeIf(String::isNotBlank) ?: leg.lineId?.takeIf(String::isNotBlank)
            val short = raw?.let { value ->
                Regex("[A-Za-zА-Яа-я]?[0-9]{1,3}").find(value)?.value
                    ?: value.substringBefore('·').substringBefore(' ').take(8)
            }
            return listOfNotNull(mode, short).joinToString(" ").trim()
        }

        private fun distanceInstruction(prefix: String, meters: Int): String = when {
            meters >= 1000 -> "$prefix ${String.format(java.util.Locale("ru"), "%.1f", meters / 1000.0)} км"
            else -> "$prefix $meters м"
        }

        private fun stopWord(count: Int): String = when {
            count % 10 == 1 && count % 100 != 11 -> "остановка"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "остановки"
            else -> "остановок"
        }

        private fun iconForMode(mode: TransportMode): Int = when (mode) {
            TransportMode.BUS -> R.drawable.ic_bus
            TransportMode.TRAM -> R.drawable.ic_tram
            TransportMode.METRO, TransportMode.MCC, TransportMode.MCD -> R.drawable.ic_metro
            TransportMode.TRAIN -> R.drawable.ic_transport
            TransportMode.WALK -> R.drawable.ic_routes
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

        private fun currentRoute(): RouteCandidate? =
            TripLiveState.current()?.route ?: ActiveTripStore.load(activity)?.route ?: LastPlanStore.seed?.route

        private fun isActiveTrip(): Boolean = routeSheet.visibility == View.VISIBLE &&
            routePrimaryAction.visibility == View.VISIBLE &&
            routePrimaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true

        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
        private fun ViewGroup.childAtOrNull(index: Int): View? = if (index in 0 until childCount) getChildAt(index) else null
    }

    private const val ACTIVE_TOP_TAG = "reference_active_trip_top"
    private const val ACTIVE_MINI_TAG = "reference_active_trip_mini"
    private const val DETAIL_CARD_TAG = "vh_gps_current_stage_card"
}

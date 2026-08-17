package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max

/**
 * Data-only active-trip binder. Geometry remains owned by ResponsiveProductUi.
 *
 * It updates the existing top/mini trip chrome and one compact GPS status card when passenger GPS
 * moves through the route. It never changes sheet size, screen visibility or navigation state.
 */
internal object TripProgressUiController {
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
            if (activity.isFinishing || activity.isDestroyed) return
            val route = currentRoute()?.takeIf { it.id == snapshot.routeId } ?: return
            if (!isActiveTrip()) return
            val leg = route.legs.getOrNull(snapshot.legIndex) ?: return
            updateGpsStatus(route, leg, snapshot)
            updateTopChrome(route, leg, snapshot)
            updateMiniChrome(route, leg, snapshot)
            // Responsive chrome can be created one frame after the route sheet. Retry only once;
            // this is event-driven and never becomes a layout polling loop.
            if (root.findViewWithTag<View>(ACTIVE_TOP_TAG) == null) {
                root.postDelayed({
                    if (resumed && TripProgressState.current() == snapshot) {
                        updateTopChrome(route, leg, snapshot)
                        updateMiniChrome(route, leg, snapshot)
                    }
                }, 80L)
            }
        }

        private fun currentRoute(): RouteCandidate? =
            TripLiveState.current()?.route ?: ActiveTripStore.load(activity)?.route ?: LastPlanStore.seed?.route

        private fun isActiveTrip(): Boolean = routeSheet.visibility == View.VISIBLE &&
            routePrimaryAction.visibility == View.VISIBLE &&
            routePrimaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true

        private fun updateGpsStatus(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot) {
            var view = routePanel.findViewWithTag<TextView>(GPS_STATUS_TAG)
            if (view == null) {
                view = TextView(activity).apply {
                    tag = GPS_STATUS_TAG
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_primary))
                    background = rounded(color(R.color.vh_primary_soft), 14f)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                }
                routePanel.addView(
                    view,
                    minOf(1, routePanel.childCount),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(5) }
                )
            }
            val instruction = instruction(route, leg, snapshot)
            view.text = "GPS · этап ${snapshot.legIndex + 1}/${route.legs.size} · $instruction"
            view.contentDescription = "GPS прогресс. Этап ${snapshot.legIndex + 1} из ${route.legs.size}. $instruction"
        }

        private fun updateTopChrome(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot) {
            val top = root.findViewWithTag<ViewGroup>(ACTIVE_TOP_TAG) ?: return
            top.contentDescription = "Активная поездка по GPS. ${instruction(route, leg, snapshot)}"

            val header = top.getChildAtOrNull(0) as? LinearLayout
            val badge = header?.getChildAtOrNull(0) as? TextView
            val headerCopy = header?.getChildAtOrNull(1) as? LinearLayout
            val title = headerCopy?.getChildAtOrNull(0) as? TextView
            val subtitle = headerCopy?.getChildAtOrNull(1) as? TextView
            badge?.let { updateBadge(it, leg) }
            title?.text = phaseTitle(snapshot.phase, leg.mode)
            subtitle?.text = phaseSubtitle(route, leg, snapshot)

            val station = top.getChildAtOrNull(1) as? LinearLayout
            (station?.getChildAtOrNull(0) as? TextView)?.text = leg.from.name
            (station?.getChildAtOrNull(1) as? TextView)?.text = phaseCenter(snapshot.phase)
            (station?.getChildAtOrNull(2) as? TextView)?.text = leg.to.name

            val progress = top.getChildAtOrNull(2)
            if (progress is ReferenceTripProgressViewV2) {
                progress.progressFraction = snapshot.fraction.coerceIn(0.02f, 0.98f)
                progress.stopCount = leg.stopCount.coerceAtLeast(3).coerceAtMost(7)
            }

            val exit = top.getChildAtOrNull(3) as? LinearLayout
            val exitCopy = exit?.getChildAtOrNull(1) as? LinearLayout
            (exitCopy?.getChildAtOrNull(0) as? TextView)?.text = primaryInstruction(route, leg, snapshot)
            (exitCopy?.getChildAtOrNull(1) as? TextView)?.text = secondaryInstruction(route, leg, snapshot)
            (exit?.getChildAtOrNull(2) as? TextView)?.text = timeBadge(leg, snapshot)
        }

        private fun updateMiniChrome(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot) {
            val mini = root.findViewWithTag<ViewGroup>(ACTIVE_MINI_TAG) ?: return
            mini.contentDescription = "Краткий GPS статус. ${instruction(route, leg, snapshot)}"
            (mini.getChildAtOrNull(0) as? TextView)?.let { updateBadge(it, leg) }
            val copy = mini.getChildAtOrNull(1) as? LinearLayout
            (copy?.getChildAtOrNull(0) as? TextView)?.text = phaseTitle(snapshot.phase, leg.mode)
            (copy?.getChildAtOrNull(1) as? TextView)?.text = primaryInstruction(route, leg, snapshot)
            (mini.getChildAtOrNull(2) as? TextView)?.text = timeBadge(leg, snapshot).substringBefore('\n')
        }

        private fun updateBadge(view: TextView, leg: RouteLeg) {
            view.text = shortBadge(leg)
            view.setTextColor(Color.WHITE)
            view.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            view.background = rounded(modeColor(leg.mode), 14f)
        }

        private fun instruction(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot): String =
            "${phaseTitle(snapshot.phase, leg.mode)} · ${primaryInstruction(route, leg, snapshot)}"

        private fun phaseTitle(phase: TripProgressPhase, mode: TransportMode): String = when (phase) {
            TripProgressPhase.APPROACH -> "Идём к остановке"
            TripProgressPhase.WAITING -> "Ждём ${modeNoun(mode)}"
            TripProgressPhase.ONBOARD -> "В пути"
            TripProgressPhase.ALIGHTING -> "Скоро выход"
            TripProgressPhase.TRANSFER -> "Пересадка"
            TripProgressPhase.FINAL_WALK -> "Идём к месту"
            TripProgressPhase.FINISHED -> "Вы прибыли"
            TripProgressPhase.OFF_ROUTE -> "Возвращаемся к маршруту"
        }

        private fun phaseSubtitle(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot): String = when (snapshot.phase) {
            TripProgressPhase.APPROACH -> "До ${leg.to.name}"
            TripProgressPhase.WAITING -> "${leg.from.name} · ${lineLabel(leg)}"
            TripProgressPhase.ONBOARD, TripProgressPhase.ALIGHTING -> "В направлении ${leg.to.name}"
            TripProgressPhase.TRANSFER -> nextTransit(route, snapshot)?.let { "Переход к ${lineLabel(it)}" } ?: "Переход"
            TripProgressPhase.FINAL_WALK -> "До ${leg.to.name}"
            TripProgressPhase.FINISHED -> leg.to.name
            TripProgressPhase.OFF_ROUTE -> "GPS уточняет текущий этап"
        }

        private fun primaryInstruction(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot): String = when (snapshot.phase) {
            TripProgressPhase.APPROACH -> distanceText("До остановки", snapshot.distanceRemainingMeters)
            TripProgressPhase.WAITING -> "Посадка: ${lineLabel(leg)}"
            TripProgressPhase.ONBOARD -> if (snapshot.remainingStops > 0) {
                "Выходите через ${snapshot.remainingStops} ${stopWord(snapshot.remainingStops)}"
            } else {
                "Следующая — выход"
            }
            TripProgressPhase.ALIGHTING -> if (snapshot.remainingStops > 1) {
                "Выходите через ${snapshot.remainingStops} ${stopWord(snapshot.remainingStops)}"
            } else {
                "Выходите на следующей"
            }
            TripProgressPhase.TRANSFER -> nextTransit(route, snapshot)?.let { "Переход к ${lineLabel(it)}" } ?: "Продолжайте переход"
            TripProgressPhase.FINAL_WALK -> distanceText("До места", snapshot.distanceRemainingMeters)
            TripProgressPhase.FINISHED -> "Маршрут завершён"
            TripProgressPhase.OFF_ROUTE -> "Проверяем положение на маршруте"
        }

        private fun secondaryInstruction(route: RouteCandidate, leg: RouteLeg, snapshot: TripProgressSnapshot): String = when (snapshot.phase) {
            TripProgressPhase.TRANSFER -> nextTransit(route, snapshot)?.from?.name ?: leg.to.name
            TripProgressPhase.FINISHED -> "Вы в точке назначения"
            else -> leg.to.name
        }

        private fun timeBadge(leg: RouteLeg, snapshot: TripProgressSnapshot): String {
            if (snapshot.phase == TripProgressPhase.FINISHED) return "Готово"
            if (snapshot.phase in setOf(TripProgressPhase.APPROACH, TripProgressPhase.TRANSFER, TripProgressPhase.FINAL_WALK)) {
                return if (snapshot.distanceRemainingMeters >= 1000) {
                    String.format(java.util.Locale("ru"), "%.1f км", snapshot.distanceRemainingMeters / 1000.0)
                } else {
                    "${snapshot.distanceRemainingMeters} м"
                }
            }
            val until = when (snapshot.phase) {
                TripProgressPhase.WAITING -> leg.departureEpochSec
                else -> leg.arrivalEpochSec
            }
            val minutes = max(1, ceil((until - snapshot.epochSec).coerceAtLeast(0L) / 60.0).toInt())
            return if (snapshot.phase == TripProgressPhase.WAITING) "$minutes мин\nдо посадки" else "$minutes мин\nдо пересадки"
        }

        private fun phaseCenter(phase: TripProgressPhase): String = when (phase) {
            TripProgressPhase.WAITING -> "Посадка"
            TripProgressPhase.ALIGHTING -> "Выход"
            TripProgressPhase.TRANSFER -> "Переход"
            TripProgressPhase.FINISHED -> "Финиш"
            else -> "Сейчас"
        }

        private fun nextTransit(route: RouteCandidate, snapshot: TripProgressSnapshot): RouteLeg? =
            route.legs.drop(snapshot.legIndex + 1).firstOrNull { it.mode != TransportMode.WALK }

        private fun lineLabel(leg: RouteLeg): String {
            val line = leg.lineName?.takeIf { it.isNotBlank() } ?: leg.lineId?.takeIf { it.isNotBlank() }
            return listOf(modeNoun(leg.mode), line).filterNotNull().joinToString(" ").trim()
        }

        private fun shortBadge(leg: RouteLeg): String {
            if (leg.mode == TransportMode.WALK) return "Пешком"
            val raw = leg.lineName?.takeIf { it.isNotBlank() } ?: leg.lineId.orEmpty()
            val clean = raw.replace(Regex("\\s+"), " ").trim()
            val number = Regex("[A-Za-zА-Яа-я]?[0-9]{1,3}").find(clean)?.value
            return when (leg.mode) {
                TransportMode.MCC -> "МЦК"
                TransportMode.MCD -> number ?: "МЦД"
                TransportMode.METRO -> number ?: "Метро"
                TransportMode.BUS, TransportMode.TRAM -> clean.substringBefore('·').substringBefore(' ').take(5).ifBlank { modeNoun(leg.mode) }
                TransportMode.TRAIN -> number ?: "Поезд"
                TransportMode.WALK -> "Пешком"
            }
        }

        private fun modeNoun(mode: TransportMode): String = when (mode) {
            TransportMode.WALK -> "переход"
            TransportMode.BUS -> "автобус"
            TransportMode.TRAM -> "трамвай"
            TransportMode.METRO -> "метро"
            TransportMode.MCC -> "МЦК"
            TransportMode.MCD -> "МЦД"
            TransportMode.TRAIN -> "поезд"
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

        private fun distanceText(prefix: String, meters: Int): String = if (meters >= 1000) {
            "$prefix ${String.format(java.util.Locale("ru"), "%.1f", meters / 1000.0)} км"
        } else {
            "$prefix $meters м"
        }

        private fun stopWord(count: Int): String = when {
            count % 10 == 1 && count % 100 != 11 -> "остановку"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "остановки"
            else -> "остановок"
        }

        private fun rounded(fill: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
        }

        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
        private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

        private fun ViewGroup.getChildAtOrNull(index: Int): View? = if (index in 0 until childCount) getChildAt(index) else null
    }

    private const val ACTIVE_TOP_TAG = "reference_active_trip_top"
    private const val ACTIVE_MINI_TAG = "reference_active_trip_mini"
    private const val GPS_STATUS_TAG = "vh_unified_gps_status"
}

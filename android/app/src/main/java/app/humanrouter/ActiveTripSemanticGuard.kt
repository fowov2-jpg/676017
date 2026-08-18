package app.humanrouter

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import java.time.Instant
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max

/** Keeps reference active-trip chrome on the same leg as GPS/detail state and enforces no third bar. */
internal object ActiveTripSemanticGuard {
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
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        private val primary = activity.findViewById<Button>(R.id.routePrimaryAction)
        private val bottomNav = activity.findViewById<View>(R.id.bottomNav)
        private var immediatePosted = false
        private val delayed = mutableListOf<Runnable>()

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            schedule(0L)
        }
        private val progressListener: (TripProgressSnapshot) -> Unit = {
            activity.runOnUiThread { schedule(0L) }
        }
        private val liveListener: (TripLiveSnapshot) -> Unit = {
            activity.runOnUiThread { schedule(0L) }
        }

        init {
            root.addOnLayoutChangeListener(layoutListener)
            sheet.addOnLayoutChangeListener(layoutListener)
            TripProgressState.addListener(progressListener)
            TripLiveState.addListener(liveListener)
            schedule(0L)
            schedule(260L)
            schedule(820L)
            schedule(1_800L)
            // ReferenceVisualTuning has a final finite pass at 3 seconds; converge after it.
            schedule(3_250L)
        }

        fun destroy() {
            root.removeOnLayoutChangeListener(layoutListener)
            sheet.removeOnLayoutChangeListener(layoutListener)
            TripProgressState.removeListener(progressListener)
            TripLiveState.removeListener(liveListener)
            delayed.forEach(root::removeCallbacks)
            delayed.clear()
        }

        private fun schedule(delayMs: Long) {
            if (activity.isDestroyed || activity.isFinishing) return
            if (delayMs == 0L) {
                if (immediatePosted) return
                immediatePosted = true
                root.post {
                    immediatePosted = false
                    reconcile()
                }
                return
            }
            val task = Runnable { reconcile() }
            delayed += task
            root.postDelayed(task, delayMs)
        }

        private fun isActiveTrip(): Boolean =
            sheet.visibility == View.VISIBLE &&
                primary.visibility == View.VISIBLE &&
                primary.text?.toString()?.contains("Заверш", ignoreCase = true) == true

        private fun reconcile() {
            if (!isActiveTrip()) return
            val route = TripLiveState.current()?.route
                ?: LastPlanStore.seed?.route
                ?: ActiveTripStore.load(activity)?.route
                ?: return
            val now = Instant.now().epochSecond
            val snapshot = TripProgressState.current()?.takeIf { it.routeId == route.id }
            val leg = snapshot?.let { route.legs.getOrNull(it.legIndex) }
                ?: currentLegBySchedule(route, now)
                ?: return

            val remainingMinutes = max(
                1,
                ceil((leg.arrivalEpochSec - now).coerceAtLeast(0L) / 60.0).toInt()
            )
            val remainingMeters = snapshot?.distanceRemainingMeters?.coerceAtLeast(0)
                ?: leg.walkMeters.coerceAtLeast(0)
            val remainingStops = snapshot?.remainingStops?.coerceAtLeast(0)
                ?: estimatedRemainingStops(leg, now)

            root.findViewWithTag<ViewGroup>(TOP_TAG)?.let { top ->
                reconcileTop(top, leg, remainingMinutes, remainingMeters, remainingStops)
            }
            root.findViewWithTag<ViewGroup>(MINI_TAG)?.let { mini ->
                // Keep the legacy view internally for compatibility with the existing reference
                // builder, but never expose it as a third product bar below the active-trip sheet.
                reconcileMini(mini, leg, remainingMeters, remainingStops)
                if (mini.visibility != View.GONE) mini.visibility = View.GONE
            }
            enforceTripSheetBottomInset()
        }

        private fun enforceTripSheetBottomInset() {
            val navBottom = (bottomNav.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: 0
            val params = sheet.layoutParams as? FrameLayout.LayoutParams ?: return
            val targetBottom = navBottom + dp(8)
            if (params.bottomMargin != targetBottom) {
                params.bottomMargin = targetBottom
                sheet.layoutParams = params
            }
        }

        private fun reconcileTop(
            top: ViewGroup,
            leg: RouteLeg,
            remainingMinutes: Int,
            remainingMeters: Int,
            remainingStops: Int
        ) {
            val texts = descendantTextViews(top).toList()
            val badge = texts.firstOrNull()
            badge?.let { setBadge(it, leg) }

            texts.firstOrNull {
                it !== badge && it.text?.toString() in setOf("В пути", "Пешком")
            }?.let { title ->
                setText(title, if (leg.mode == TransportMode.WALK) "Пешком" else "В пути")
            }

            texts.firstOrNull {
                val value = it.text?.toString().orEmpty()
                value.startsWith("В направлении ") || value.startsWith("До ")
            }?.let { direction ->
                setText(
                    direction,
                    if (leg.mode == TransportMode.WALK) "До ${leg.to.name}" else "В направлении ${leg.to.name}"
                )
            }

            texts.firstOrNull {
                val value = it.text?.toString().orEmpty()
                value.startsWith("Выходите через ") ||
                    value == "Следующая — выход" ||
                    value.startsWith("Пешком ")
            }?.let { instruction ->
                val copy = if (leg.mode == TransportMode.WALK) {
                    "Пешком $remainingMeters м"
                } else if (remainingStops > 0) {
                    "Выходите через $remainingStops ${stopWord(remainingStops)}"
                } else {
                    "Следующая — выход"
                }
                setText(instruction, copy)
            }

            texts.firstOrNull {
                val value = it.text?.toString().orEmpty()
                value.contains("до пересадки") || value.contains("до следующего этапа")
            }?.let { eta ->
                setText(
                    eta,
                    if (leg.mode == TransportMode.WALK) {
                        "$remainingMinutes мин\nдо следующего этапа"
                    } else {
                        "$remainingMinutes мин\nдо пересадки"
                    }
                )
            }
        }

        private fun reconcileMini(
            mini: ViewGroup,
            leg: RouteLeg,
            remainingMeters: Int,
            remainingStops: Int
        ) {
            val texts = descendantTextViews(mini).toList()
            val badge = texts.firstOrNull()
            badge?.let { setBadge(it, leg) }

            texts.firstOrNull {
                val value = it.text?.toString().orEmpty()
                value.startsWith("В пути до ") || value.startsWith("Пешком до ")
            }?.let { title ->
                setText(
                    title,
                    if (leg.mode == TransportMode.WALK) "Пешком до ${leg.to.name}" else "В пути до ${leg.to.name}"
                )
            }

            texts.firstOrNull {
                val value = it.text?.toString().orEmpty()
                value.startsWith("Выходите через ") ||
                    value == "Следующая — выход" ||
                    value.endsWith(" м до точки")
            }?.let { instruction ->
                val copy = if (leg.mode == TransportMode.WALK) {
                    "$remainingMeters м до точки"
                } else if (remainingStops > 0) {
                    "Выходите через $remainingStops ${stopWord(remainingStops)}"
                } else {
                    "Следующая — выход"
                }
                setText(instruction, copy)
            }
        }

        private fun currentLegBySchedule(route: RouteCandidate, now: Long): RouteLeg? =
            route.legs.firstOrNull { now >= it.departureEpochSec && now < it.arrivalEpochSec }
                ?: route.legs.firstOrNull { now < it.arrivalEpochSec }
                ?: route.legs.lastOrNull()

        private fun estimatedRemainingStops(leg: RouteLeg, now: Long): Int {
            if (leg.stopCount <= 0) return 0
            val duration = (leg.arrivalEpochSec - leg.departureEpochSec).coerceAtLeast(1L)
            val elapsed = (now - leg.departureEpochSec).coerceIn(0L, duration)
            val fractionRemaining = 1.0 - elapsed.toDouble() / duration.toDouble()
            return ceil(leg.stopCount * fractionRemaining).toInt().coerceIn(0, leg.stopCount)
        }

        private fun setBadge(view: TextView, leg: RouteLeg) {
            val label = badgeLabel(leg)
            if (view.text?.toString() != label) view.text = label
            val current = view.compoundDrawablesRelative.firstOrNull()
            if (current == null || view.getTag(R.id.routeFiltersPanel) != leg.mode.name) {
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(iconForMode(leg.mode), 0, 0, 0)
                view.setTag(R.id.routeFiltersPanel, leg.mode.name)
            }
        }

        private fun badgeLabel(leg: RouteLeg): String {
            if (leg.mode == TransportMode.WALK) return "ПЕШ"
            val raw = leg.lineName?.takeIf(String::isNotBlank)
                ?: leg.lineId?.takeIf(String::isNotBlank)
                ?: return modeLabel(leg.mode)
            return Regex("[A-Za-zА-Яа-я]?[0-9]{1,3}").find(raw)?.value
                ?: raw.substringBefore('·').substringBefore(' ').take(8)
        }

        private fun setText(view: TextView, copy: String) {
            if (view.text?.toString() != copy) view.text = copy
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

        private fun iconForMode(mode: TransportMode): Int = when (mode) {
            TransportMode.WALK -> R.drawable.ic_routes
            TransportMode.BUS -> R.drawable.ic_bus
            TransportMode.TRAM -> R.drawable.ic_tram
            TransportMode.METRO, TransportMode.MCC, TransportMode.MCD -> R.drawable.ic_metro
            TransportMode.TRAIN -> R.drawable.ic_transport
        }

        private fun stopWord(count: Int): String = when {
            count % 10 == 1 && count % 100 != 11 -> "остановку"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "остановки"
            else -> "остановок"
        }

        private fun descendantTextViews(view: View): Sequence<TextView> = sequence {
            if (view is TextView) yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    yieldAll(descendantTextViews(view.getChildAt(index)))
                }
            }
        }

        private fun dp(value: Int): Int =
            (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }

    private const val TOP_TAG = "reference_active_trip_top"
    private const val MINI_TAG = "reference_active_trip_mini"
}

package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.RoutePresentation
import app.humanrouter.routing.TransportMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
        private val routeScroll = activity.findViewById<ScrollView>(R.id.routeResultsScroll)
        private val routePanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private val primary = activity.findViewById<Button>(R.id.routePrimaryAction)
        private val bottomNav = activity.findViewById<View>(R.id.bottomNav)
        private val resolverExecutor = Executors.newSingleThreadExecutor()
        private val resolvedStops = HashMap<String, List<ActiveTripResolvedStop>>()
        private val pendingStopKeys = HashSet<String>()
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val zoneId = ZoneId.of("Europe/Moscow")
        private var immediatePosted = false
        private var lastTimelineAnchorKey: String? = null
        private var detailedTimelineSignature: String? = null
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
            resolverExecutor.shutdownNow()
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
            if (!isActiveTrip()) {
                lastTimelineAnchorKey = null
                removeDetailedTimeline()
                return
            }
            val route = TripLiveState.current()?.route
                ?: LastPlanStore.seed?.route
                ?: ActiveTripStore.load(activity)?.route
                ?: return
            val wallNow = Instant.now().epochSecond
            val snapshot = TripProgressState.current()?.takeIf { it.routeId == route.id }
            val leg = snapshot?.let { route.legs.getOrNull(it.legIndex) }
                ?: currentLegBySchedule(route, wallNow)
                ?: return
            // Production GPS and deterministic replay both own progress time. Wall-clock is only the
            // fallback before a location snapshot exists, so ETA cannot drift away from the state
            // that selected the current stop/stage.
            val stateNow = snapshot?.epochSec ?: wallNow

            val remainingMinutes = max(
                1,
                ceil((leg.arrivalEpochSec - stateNow).coerceAtLeast(0L) / 60.0).toInt()
            )
            val remainingMeters = snapshot?.distanceRemainingMeters?.coerceAtLeast(0)
                ?: leg.walkMeters.coerceAtLeast(0)
            val remainingStops = snapshot?.remainingStops?.coerceAtLeast(0)
                ?: estimatedRemainingStops(leg, stateNow)

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
            if (!reconcileDetailedStopTimeline(route, leg, snapshot, stateNow)) {
                enforceTimelineFirstViewport(route, leg)
            }
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

        private fun stopKey(route: RouteCandidate, leg: RouteLeg): String = listOf(
            route.id,
            leg.mode.name,
            leg.lineId.orEmpty(),
            leg.from.id,
            leg.to.id,
            leg.departureEpochSec,
            leg.arrivalEpochSec,
            leg.stopCount
        ).joinToString(":")

        /**
         * Prefer stop/station-level data only when it can be reconstructed from a trusted runtime
         * source. Resolution is intentionally off the UI thread. An unresolved leg stays on the
         * aggregate timeline instead of fabricating passenger information.
         */
        private fun reconcileDetailedStopTimeline(
            route: RouteCandidate,
            leg: RouteLeg,
            snapshot: TripProgressSnapshot?,
            now: Long
        ): Boolean {
            if (leg.mode == TransportMode.WALK || leg.stopCount <= 0 || leg.lineId.isNullOrBlank()) {
                removeDetailedTimeline()
                return false
            }
            val key = stopKey(route, leg)
            val cached = resolvedStops[key]
            if (cached == null && key !in pendingStopKeys) {
                pendingStopKeys += key
                resolverExecutor.execute {
                    val value = ActiveTripStopTimelineResolver.resolve(activity.applicationContext, leg)
                    activity.runOnUiThread {
                        pendingStopKeys.remove(key)
                        resolvedStops[key] = value
                        schedule(0L)
                    }
                }
            }
            if (cached.isNullOrEmpty()) {
                removeDetailedTimeline()
                return false
            }
            renderDetailedTimeline(key, leg, snapshot, now, cached)
            return true
        }

        private fun renderDetailedTimeline(
            key: String,
            leg: RouteLeg,
            snapshot: TripProgressSnapshot?,
            now: Long,
            stops: List<ActiveTripResolvedStop>
        ) {
            val currentIndex = currentStopIndex(stops, snapshot, now)
            val windowStart = stopWindowStart(currentIndex, stops.size)
            val windowEnd = min(stops.lastIndex, windowStart + DETAIL_WINDOW_SIZE - 1)
            val phase = snapshot?.phase?.name.orEmpty()
            val signature = buildString {
                append(key).append(':').append(currentIndex).append(':').append(windowStart).append('-').append(windowEnd)
                append(':').append(phase).append(':').append(snapshot?.remainingStops ?: -1)
                for (index in windowStart..windowEnd) {
                    val stop = stops[index]
                    append('|').append(stop.name).append('@').append(stop.arrivalEpochSec)
                }
            }

            var container = routePanel.findViewWithTag<LinearLayout>(DETAIL_TIMELINE_TAG)
            if (container == null) {
                container = LinearLayout(activity).apply {
                    tag = DETAIL_TIMELINE_TAG
                    orientation = LinearLayout.VERTICAL
                    contentDescription = "Остановки текущего транспорта"
                }
                routePanel.addView(
                    container,
                    0,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                detailedTimelineSignature = null
            }
            if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE
            for (index in 0 until routePanel.childCount) {
                val child = routePanel.getChildAt(index)
                if (child !== container && child.visibility != View.GONE) child.visibility = View.GONE
            }

            if (detailedTimelineSignature != signature) {
                container.removeAllViews()
                for (index in windowStart..windowEnd) {
                    container.addView(
                        detailedStopRow(
                            stop = stops[index],
                            index = index,
                            total = stops.size,
                            currentIndex = currentIndex,
                            leg = leg,
                            snapshot = snapshot
                        )
                    )
                }
                detailedTimelineSignature = signature
                routeScroll.post {
                    if (!activity.isFinishing && !activity.isDestroyed) routeScroll.scrollTo(0, 0)
                }
            }
        }

        private fun currentStopIndex(
            stops: List<ActiveTripResolvedStop>,
            snapshot: TripProgressSnapshot?,
            now: Long
        ): Int {
            if (snapshot != null) {
                return when (snapshot.phase) {
                    TripProgressPhase.APPROACH, TripProgressPhase.WAITING -> 0
                    TripProgressPhase.ONBOARD, TripProgressPhase.ALIGHTING ->
                        (stops.lastIndex - snapshot.remainingStops.coerceAtLeast(0)).coerceIn(0, stops.lastIndex)
                    TripProgressPhase.TRANSFER, TripProgressPhase.FINAL_WALK, TripProgressPhase.FINISHED -> stops.lastIndex
                    TripProgressPhase.OFF_ROUTE -> stops.indices.minByOrNull { index ->
                        kotlin.math.abs(stops[index].arrivalEpochSec - now)
                    } ?: 0
                }
            }
            return stops.indexOfLast { now >= it.departureEpochSec }
                .coerceAtLeast(0)
                .coerceAtMost(stops.lastIndex)
        }

        private fun stopWindowStart(currentIndex: Int, size: Int): Int {
            if (size <= DETAIL_WINDOW_SIZE) return 0
            var start = (currentIndex - 1).coerceAtLeast(0)
            if (start + DETAIL_WINDOW_SIZE > size) start = size - DETAIL_WINDOW_SIZE
            return start.coerceAtLeast(0)
        }

        /**
         * Approved active-trip composition: progress rail on the left, stop copy in the middle and
         * clock time aligned to the right edge. Past/current rail segments are primary; only future
         * segments remain neutral. This is deliberately data-only styling: stop names/times still
         * come exclusively from the trusted resolver.
         */
        private fun detailedStopRow(
            stop: ActiveTripResolvedStop,
            index: Int,
            total: Int,
            currentIndex: Int,
            leg: RouteLeg,
            snapshot: TripProgressSnapshot?
        ): View = LinearLayout(activity).apply {
            val current = index == currentIndex
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(55)
            tag = "vh_active_stop_row:$index"
            contentDescription = "Остановка маршрута: ${stop.name}, ${formatTime(stop.arrivalEpochSec)}"
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(color(if (current) R.color.vh_primary_soft else R.color.vh_surface_solid))
            }

            addView(FrameLayout(activity).apply {
                if (index > 0) {
                    val topPast = index <= currentIndex
                    addView(View(activity).apply {
                        setBackgroundColor(color(if (topPast) R.color.vh_primary else R.color.vh_border))
                    }, FrameLayout.LayoutParams(dp(2), dp(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                }
                if (index < total - 1) {
                    val bottomPast = index < currentIndex
                    addView(View(activity).apply {
                        setBackgroundColor(color(if (bottomPast) R.color.vh_primary else R.color.vh_border))
                    }, FrameLayout.LayoutParams(dp(2), dp(29), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                }
                addView(View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color(if (index <= currentIndex) R.color.vh_primary else R.color.vh_border))
                        if (current) setStroke(dp(3), color(R.color.vh_surface_solid))
                    }
                }, FrameLayout.LayoutParams(dp(if (current) 16 else 10), dp(if (current) 16 else 10), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(28), dp(55)))

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = stop.name
                    textSize = if (current) 15f else 14f
                    maxLines = 2
                    includeFontPadding = false
                    setTextColor(color(R.color.vh_text_primary))
                    if (current) setTypeface(typeface, Typeface.BOLD)
                })
                detailedInstruction(index, total, currentIndex, leg, snapshot)?.let { copy ->
                    addView(TextView(activity).apply {
                        text = copy
                        textSize = 11.5f
                        includeFontPadding = false
                        setTextColor(color(if (current) R.color.vh_primary else R.color.vh_text_tertiary))
                        if (current) setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(7)
                rightMargin = dp(8)
            })

            addView(TextView(activity).apply {
                text = formatTime(if (index == 0) stop.departureEpochSec else stop.arrivalEpochSec)
                textSize = 12f
                includeFontPadding = false
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(color(if (current) R.color.vh_primary else R.color.vh_text_tertiary))
                if (current) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.MATCH_PARENT))
        }

        private fun detailedInstruction(
            index: Int,
            total: Int,
            currentIndex: Int,
            leg: RouteLeg,
            snapshot: TripProgressSnapshot?
        ): String? {
            val current = index == currentIndex
            if (!current) {
                if (
                    index == currentIndex - 1 &&
                    snapshot?.phase in setOf(TripProgressPhase.ONBOARD, TripProgressPhase.ALIGHTING)
                ) {
                    return "Вы сели в ${boardingNoun(leg.mode)}"
                }
                return if (index == total - 1) "Выход" else null
            }
            return when (snapshot?.phase) {
                TripProgressPhase.WAITING -> "Посадка"
                TripProgressPhase.ONBOARD -> if (snapshot.remainingStops > 0) {
                    "Выходите через ${snapshot.remainingStops} ${stopWord(snapshot.remainingStops)}"
                } else "Следующая — выход"
                TripProgressPhase.ALIGHTING -> "Следующая — выход"
                TripProgressPhase.OFF_ROUTE -> "GPS уточняет положение"
                else -> "Сейчас"
            }
        }

        private fun boardingNoun(mode: TransportMode): String = when (mode) {
            TransportMode.BUS -> "автобус"
            TransportMode.TRAM -> "трамвай"
            TransportMode.METRO -> "метро"
            TransportMode.MCC -> "МЦК"
            TransportMode.MCD -> "МЦД"
            TransportMode.TRAIN -> "поезд"
            TransportMode.WALK -> "маршрут"
        }

        private fun removeDetailedTimeline() {
            val detailed = routePanel.findViewWithTag<View>(DETAIL_TIMELINE_TAG)
            if (detailed != null) routePanel.removeView(detailed)
            detailedTimelineSignature = null
            for (index in 0 until routePanel.childCount) {
                val child = routePanel.getChildAt(index)
                if (child.contentDescription?.toString()?.startsWith(TIMELINE_PREFIX) == true && child.visibility != View.VISIBLE) {
                    child.visibility = View.VISIBLE
                }
            }
        }

        /**
         * The passenger reference starts the bottom sheet with the journey timeline itself. The top
         * floating card already owns current-stage status, so repeating aggregate summary, mode chips
         * and a second current-stage card above the timeline only pushes actionable stops below the
         * initial viewport. Keep those legacy children for binder compatibility, but hide them.
         *
         * GPS can advance independently from schedule time. When the active display step changes,
         * anchor the sheet one row before it so the user sees current context plus what comes next.
         * Do not keep forcing scroll position after that transition: manual scrolling must remain
         * user-owned. Only the actual current row receives the soft highlight.
         */
        private fun enforceTimelineFirstViewport(route: RouteCandidate, leg: RouteLeg) {
            val timelineRows = buildList<View> {
                for (index in 0 until routePanel.childCount) {
                    val child = routePanel.getChildAt(index)
                    if (child.contentDescription?.toString()?.startsWith(TIMELINE_PREFIX) == true) add(child)
                }
            }
            if (timelineRows.isEmpty()) return
            val firstTimelineIndex = routePanel.indexOfChild(timelineRows.first())
            if (firstTimelineIndex < 0) return

            var changed = false
            for (index in 0 until firstTimelineIndex) {
                val child = routePanel.getChildAt(index)
                if (child.visibility != View.GONE) {
                    child.visibility = View.GONE
                    changed = true
                }
            }

            val steps = RoutePresentation.steps(route)
            val currentStepIndex = steps.indexOfFirst { step ->
                val modeMatches = if (leg.mode == TransportMode.WALK) step.mode == null else step.mode == leg.mode
                modeMatches &&
                    leg.departureEpochSec >= step.departureEpochSec &&
                    leg.arrivalEpochSec <= step.arrivalEpochSec
            }.takeIf { it >= 0 }
                ?.coerceAtMost(timelineRows.lastIndex)
                ?: 0

            val activeColor = ColorStateList.valueOf(color(R.color.vh_primary_soft))
            val idleColor = ColorStateList.valueOf(color(R.color.vh_surface_solid))
            timelineRows.forEachIndexed { index, row ->
                val card = (row as? ViewGroup)?.getChildAt(1)
                val tint = if (index == currentStepIndex) activeColor else idleColor
                if (card != null && card.backgroundTintList?.defaultColor != tint.defaultColor) {
                    card.backgroundTintList = tint
                }
            }

            val anchorKey = "${route.id}:$currentStepIndex"
            if (changed || anchorKey != lastTimelineAnchorKey) {
                lastTimelineAnchorKey = anchorKey
                val anchorIndex = (currentStepIndex - 1).coerceAtLeast(0).coerceAtMost(timelineRows.lastIndex)
                val anchor = timelineRows[anchorIndex]
                routeScroll.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        routeScroll.scrollTo(0, anchor.top.coerceAtLeast(0))
                    }
                }
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

        private fun formatTime(epochSec: Long): String = Instant.ofEpochSecond(epochSec)
            .atZone(zoneId)
            .format(timeFormatter)

        private fun descendantTextViews(view: View): Sequence<TextView> = sequence {
            if (view is TextView) yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    yieldAll(descendantTextViews(view.getChildAt(index)))
                }
            }
        }

        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
        private fun dp(value: Int): Int =
            (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }

    private const val TOP_TAG = "reference_active_trip_top"
    private const val MINI_TAG = "reference_active_trip_mini"
    private const val TIMELINE_PREFIX = "Этап маршрута:"
    private const val DETAIL_TIMELINE_TAG = "vh_active_stop_timeline"
    private const val DETAIL_WINDOW_SIZE = 4
}

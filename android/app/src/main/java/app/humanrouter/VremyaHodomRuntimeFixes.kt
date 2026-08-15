package app.humanrouter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runtime UX controller for the phone defects that deterministic screenshot fixtures did not catch.
 * It intentionally works alongside MainActivity so the production screen and routing engine remain
 * the source of truth while sheet gestures, live refresh, GPS progress and per-leg map styling are
 * independently regression-testable.
 */
internal object VremyaHodomRuntimeFixes : Application.ActivityLifecycleCallbacks {
    private val controllers = WeakHashMap<Activity, Controller>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is MainActivity) controllers[activity] = Controller(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        controllers[activity]?.resume()
    }

    override fun onActivityPaused(activity: Activity) {
        controllers[activity]?.pause()
    }

    override fun onActivityDestroyed(activity: Activity) {
        controllers.remove(activity)?.destroy()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private class Controller(private val activity: MainActivity) {
        private val handler = Handler(Looper.getMainLooper())
        private val root: View = activity.findViewById(R.id.root)
        private val routeSheet: View = activity.findViewById(R.id.routeResultsContainer)
        private val routePanel: LinearLayout = activity.findViewById(R.id.routeResultsPanel)
        private val routeScroll: ScrollView = activity.findViewById(R.id.routeResultsScroll)
        private val mapView: MapView = activity.findViewById(R.id.mapView)
        private var resumed = false
        private var collapsed = false
        private var lastRenderedRouteId: String? = null

        private val liveListener: (TripLiveSnapshot) -> Unit = { snapshot ->
            handler.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    setPrivateActiveRoute(snapshot.route)
                    refreshActiveTrip(snapshot.route, force = true)
                }
            }
        }

        private val ticker = object : Runnable {
            override fun run() {
                if (!resumed) return
                activeRoute()?.let { refreshActiveTrip(it, force = false) }
                handler.postDelayed(this, ACTIVE_TRIP_REFRESH_MS)
            }
        }

        init {
            installSheetGesture()
            val stored = ActiveTripStore.load(activity)?.route
            if (TripLiveState.current() == null && stored != null) TripLiveState.publish(stored)
            TripLiveState.addListener(liveListener)
            root.post {
                activeRoute()?.let {
                    applyRouteMapStyle(it)
                    patchGpsPresentation(it)
                }
            }
        }

        fun resume() {
            resumed = true
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        fun pause() {
            resumed = false
            handler.removeCallbacks(ticker)
        }

        fun destroy() {
            pause()
            TripLiveState.removeListener(liveListener)
            routeSheet.setOnTouchListener(null)
        }

        private fun activeRoute(): RouteCandidate? =
            TripLiveState.current()?.route
                ?: readPrivateActiveRoute()
                ?: ActiveTripStore.load(activity)?.route
                ?: LastPlanStore.seed?.route

        private fun refreshActiveTrip(route: RouteCandidate, force: Boolean) {
            val visible = routeSheet.visibility == View.VISIBLE
            if (!visible) {
                applyRouteMapStyle(route)
                return
            }
            val scrollY = routeScroll.scrollY
            val previousHeight = routeSheet.height
            val shouldRender = force || containsText(routePanel, "В пути")
            if (shouldRender) {
                invokeRenderActiveTrip(route)
                if (collapsed) setHeight(routeSheet, collapsedHeight())
                else if (previousHeight > 0 && routeSheet.height < dp(260)) setHeight(routeSheet, previousHeight)
                routeScroll.post { routeScroll.scrollTo(0, scrollY.coerceAtMost(routeScroll.getChildAt(0)?.height ?: scrollY)) }
            }
            patchGpsPresentation(route)
            patchApproximateTiming(route)
            applyRouteMapStyle(route)
            lastRenderedRouteId = route.id
        }

        private fun invokeRenderActiveTrip(route: RouteCandidate) {
            runCatching {
                val method = activity.javaClass.declaredMethods.firstOrNull {
                    it.name == "renderActiveTrip" && it.parameterTypes.size == 1
                } ?: return
                method.isAccessible = true
                method.invoke(activity, route)
            }
        }

        private fun readPrivateActiveRoute(): RouteCandidate? = runCatching {
            val field = activity.javaClass.getDeclaredField("activeTripRoute")
            field.isAccessible = true
            field.get(activity) as? RouteCandidate
        }.getOrNull()

        private fun setPrivateActiveRoute(route: RouteCandidate) {
            runCatching {
                val field = activity.javaClass.getDeclaredField("activeTripRoute")
                field.isAccessible = true
                field.set(activity, route)
            }
        }

        private fun installSheetGesture() {
            var downY = 0f
            var startHeight = 0
            var dragging = false
            routeSheet.setOnTouchListener { view, event ->
                val handleZone = dp(56)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (event.y > handleZone) return@setOnTouchListener false
                        downY = event.rawY
                        startHeight = view.height.takeIf { it > 0 } ?: view.layoutParams.height
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (event.rawY - downY).roundToInt()
                        if (kotlin.math.abs(delta) > dp(6)) dragging = true
                        val minHeight = collapsedHeight()
                        val maxHeight = expandedHeight(startHeight)
                        setHeight(view, (startHeight - delta).coerceIn(minHeight, maxHeight))
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val minHeight = collapsedHeight()
                        val maxHeight = expandedHeight(max(startHeight, view.height))
                        val midpoint = (minHeight + maxHeight) / 2
                        collapsed = view.height < midpoint
                        animateHeight(view, if (collapsed) minHeight else maxHeight)
                        true
                    }
                    else -> false
                }
            }
        }

        private fun collapsedHeight(): Int = if (containsText(routePanel, "В пути")) dp(176) else dp(154)

        private fun expandedHeight(current: Int): Int {
            val rootHeight = root.height.takeIf { it > 0 } ?: resourcesHeightPx()
            val active = containsText(routePanel, "В пути")
            val desired = if (active) min((rootHeight * 0.66).roundToInt(), dp(560)) else min((rootHeight * 0.46).roundToInt(), dp(360))
            return max(current, max(desired, collapsedHeight() + dp(80)))
        }

        private fun animateHeight(view: View, target: Int) {
            val start = view.height
            if (start == target) return
            android.animation.ValueAnimator.ofInt(start, target).apply {
                duration = 180L
                addUpdateListener { animation -> setHeight(view, animation.animatedValue as Int) }
                start()
            }
        }

        private fun setHeight(view: View, height: Int) {
            val params = view.layoutParams
            if (params.height == height) return
            params.height = height
            view.layoutParams = params
        }

        private fun patchApproximateTiming(route: RouteCandidate) {
            val approximate = route.legs.any { leg ->
                leg.realtimeConfidence < 0.85 || leg.mode in setOf(
                    TransportMode.METRO,
                    TransportMode.MCC,
                    TransportMode.BUS,
                    TransportMode.TRAM
                )
            }
            if (!approximate) return
            allTextViews(routePanel)
                .filter { it.text?.toString()?.matches(Regex("^\\d+ мин$")) == true }
                .maxByOrNull { it.textSize }
                ?.let { if (!it.text.startsWith("≈")) it.text = "≈ ${it.text}" }

            val existing = routePanel.findViewWithTag<TextView>(DATA_NOTE_TAG)
            val note = existing ?: TextView(activity).apply {
                tag = DATA_NOTE_TAG
                textSize = 11f
                setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.vh_text_tertiary))
                setPadding(0, dp(2), 0, dp(6))
            }.also { routePanel.addView(it, 0) }
            note.text = buildString {
                append("Время приблизительное")
                if (route.legs.any { it.mode == TransportMode.BUS || it.mode == TransportMode.TRAM }) {
                    append(" · наземный транспорт по расписанию, без live")
                }
                if (route.legs.any { it.mode == TransportMode.METRO || it.mode == TransportMode.MCC }) {
                    append(" · метро/МЦК по модели")
                }
                if (route.legs.any { it.mode == TransportMode.MCD || it.mode == TransportMode.TRAIN }) {
                    append(" · ж/д: проверяйте оперативные изменения")
                }
            }
        }

        private fun patchGpsPresentation(route: RouteCandidate) {
            val location = latestLocation() ?: return
            val now = Instant.now().epochSecond
            val ageMs = System.currentTimeMillis() - location.time
            if (ageMs > MAX_LOCATION_AGE_MS || location.accuracy > MAX_LOCATION_ACCURACY_M) return
            val point = GeoPoint(location.latitude, location.longitude)
            val nearest = route.legs.mapIndexed { index, leg ->
                Triple(index, leg, leg.mapPoints().minOfOrNull { haversineMeters(point, it) } ?: Double.MAX_VALUE)
            }.minByOrNull { it.third } ?: return
            val proximityLimit = max(220.0, location.accuracy * 3.0)
            if (nearest.third > proximityLimit) return

            val label = allTextViews(routePanel).firstOrNull {
                it.text?.toString()?.startsWith("Текущий этап по расписанию") == true ||
                    it.text?.toString()?.startsWith("Текущий этап по GPS") == true
            } ?: return
            val card = label.parent as? LinearLayout ?: return
            if (card.childCount < 6) return
            val legIndex = nearest.first
            val leg = nearest.second
            val waiting = leg.mode != TransportMode.WALK && now < leg.departureEpochSec &&
                haversineMeters(point, leg.from.point) <= proximityLimit

            label.text = "Текущий этап по GPS · точность ≈${location.accuracy.roundToInt()} м"
            (card.getChildAt(1) as? TextView)?.text = if (waiting) {
                "Ожидание ${lineLabel(leg)}"
            } else {
                legTitle(route, legIndex, leg)
            }
            (card.getChildAt(2) as? TextView)?.text = "${leg.from.name} → ${leg.to.name}"

            val remaining = gpsStopsRemaining(leg, point)
            (card.getChildAt(3) as? TextView)?.text = when {
                waiting -> {
                    val minutes = max(0, ((leg.departureEpochSec - now + 59) / 60).toInt())
                    if (minutes <= 0) "Посадка ожидается сейчас" else "Посадка ориентировочно через $minutes мин"
                }
                remaining > 0 -> "До ${leg.to.name}: $remaining ${stopWord(remaining)}"
                legIndex < route.legs.lastIndex -> "Затем: ${legTitle(route, legIndex + 1, route.legs[legIndex + 1])}"
                else -> "Затем — финиш: ${leg.to.name}"
            }
            (card.getChildAt(4) as? ProgressBar)?.let { progress ->
                progress.max = route.legs.size.coerceAtLeast(1)
                progress.progress = legIndex + 1
            }
            (card.getChildAt(5) as? TextView)?.text = "Этап ${legIndex + 1} из ${route.legs.size} · положение подтверждено GPS"
        }

        private fun gpsStopsRemaining(leg: RouteLeg, point: GeoPoint): Int {
            if (leg.stopCount <= 0) return 0
            val geometry = leg.mapPoints()
            if (geometry.size < 2) return leg.stopCount
            val nearestIndex = geometry.indices.minByOrNull { haversineMeters(point, geometry[it]) } ?: 0
            val fraction = nearestIndex.toDouble() / geometry.lastIndex.toDouble()
            return (leg.stopCount * (1.0 - fraction)).roundToInt().coerceIn(0, leg.stopCount)
        }

        private fun legTitle(route: RouteCandidate, index: Int, leg: RouteLeg): String = when (leg.mode) {
            TransportMode.WALK -> {
                val transfer = index > 0 && index < route.legs.lastIndex &&
                    route.legs[index - 1].mode != TransportMode.WALK && route.legs[index + 1].mode != TransportMode.WALK
                if (transfer) "Переход" else "Пешком"
            }
            TransportMode.BUS -> "Автобус ${lineLabel(leg)}"
            TransportMode.TRAM -> "Трамвай ${lineLabel(leg)}"
            TransportMode.METRO -> "Метро ${lineLabel(leg)}"
            TransportMode.MCC -> "МЦК"
            TransportMode.MCD -> "МЦД ${lineLabel(leg)}"
            TransportMode.TRAIN -> "Поезд ${lineLabel(leg)}"
        }.trim()

        private fun lineLabel(leg: RouteLeg): String = leg.lineName?.takeIf(String::isNotBlank)
            ?: leg.lineId?.substringAfterLast(':').orEmpty()

        private fun latestLocation(): Location? {
            val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return runCatching {
                manager.getProviders(true)
                    .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                    .maxByOrNull(Location::getTime)
            }.getOrNull()
        }

        private fun applyRouteMapStyle(route: RouteCandidate) {
            if (route.id == lastRenderedRouteId && !containsText(routePanel, "В пути")) return
            mapView.getMapAsync { map ->
                map.getStyle { style ->
                    val transitFeatures = route.legs
                        .filter { it.mode != TransportMode.WALK }
                        .mapNotNull { leg -> featureForLeg(leg, transitColor(leg)) }
                    val walkFeatures = route.legs
                        .filter { it.mode == TransportMode.WALK }
                        .mapNotNull { leg -> featureForLeg(leg, WALK_COLOR) }

                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(
                        FeatureCollection.fromFeatures(transitFeatures.toTypedArray())
                    )
                    style.getLayerAs<LineLayer>(ROUTE_LAYER_ID)?.setProperties(
                        PropertyFactory.lineColor(Expression.get("segment_color")),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineOpacity(0.94f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )

                    val walkSource = style.getSourceAs<GeoJsonSource>(WALK_SOURCE_ID) ?: GeoJsonSource(
                        WALK_SOURCE_ID,
                        FeatureCollection.fromFeatures(emptyArray<Feature>())
                    ).also(style::addSource)
                    walkSource.setGeoJson(FeatureCollection.fromFeatures(walkFeatures.toTypedArray()))
                    val walkLayer = style.getLayerAs<LineLayer>(WALK_LAYER_ID) ?: LineLayer(
                        WALK_LAYER_ID,
                        WALK_SOURCE_ID
                    ).withProperties(
                        PropertyFactory.lineColor(WALK_COLOR),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineOpacity(0.88f),
                        PropertyFactory.lineDasharray(arrayOf(1.2f, 1.6f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    ).also(style::addLayer)
                    walkLayer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                }
            }
        }

        private fun featureForLeg(leg: RouteLeg, color: String): Feature? {
            val points = leg.mapPoints()
            if (points.size < 2) return null
            return Feature.fromGeometry(
                LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
            ).apply {
                addStringProperty("segment_color", color)
                addStringProperty("segment_mode", leg.mode.name)
                addStringProperty("segment_line", lineLabel(leg))
            }
        }

        private fun transitColor(leg: RouteLeg): String = when (leg.mode) {
            TransportMode.METRO -> metroColor(lineLabel(leg))
            TransportMode.MCC -> "#D4213D"
            TransportMode.MCD -> when {
                lineLabel(leg).uppercase().contains("D1") -> "#F6A800"
                lineLabel(leg).uppercase().contains("D2") -> "#E4007D"
                lineLabel(leg).uppercase().contains("D3") -> "#E85D32"
                lineLabel(leg).uppercase().contains("D4") -> "#45B8AC"
                else -> "#6A5ACD"
            }
            TransportMode.BUS -> "#1565C0"
            TransportMode.TRAM -> "#D32F2F"
            TransportMode.TRAIN -> "#5B6573"
            TransportMode.WALK -> WALK_COLOR
        }

        private fun metroColor(label: String): String {
            val normalized = label.uppercase().replace("М", "M").replace(" ", "")
            val number = Regex("(?:M)?(\\d{1,2})").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return when (number) {
                1 -> "#EF161E"
                2 -> "#2DBE2C"
                3 -> "#0078BE"
                4 -> "#00BFFF"
                5 -> "#8D5B2D"
                6 -> "#F07E24"
                7 -> "#8E479C"
                8 -> "#FFCD1C"
                9 -> "#A2A5B4"
                10 -> "#B3D445"
                11 -> "#82C0C0"
                12 -> "#A1B3D4"
                14 -> "#D4213D"
                15 -> "#D5007F"
                16 -> "#009A49"
                else -> "#287BFF"
            }
        }

        private fun containsText(root: View, needle: String): Boolean =
            allTextViews(root).any { it.text?.toString()?.contains(needle, ignoreCase = true) == true }

        private fun allTextViews(root: View): List<TextView> {
            val result = ArrayList<TextView>()
            fun visit(view: View) {
                if (view is TextView) result += view
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(root)
            return result
        }

        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()
        private fun resourcesHeightPx(): Int = activity.resources.displayMetrics.heightPixels
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val q = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * 6_371_000.0 * asin(min(1.0, sqrt(q)))
    }

    private fun stopWord(value: Int): String {
        val mod100 = value % 100
        val mod10 = value % 10
        return when {
            mod100 in 11..14 -> "остановок"
            mod10 == 1 -> "остановка"
            mod10 in 2..4 -> "остановки"
            else -> "остановок"
        }
    }

    private const val ACTIVE_TRIP_REFRESH_MS = 15_000L
    private const val MAX_LOCATION_AGE_MS = 2 * 60_000L
    private const val MAX_LOCATION_ACCURACY_M = 200f
    private const val ROUTE_SOURCE_ID = "vh-route-source"
    private const val ROUTE_LAYER_ID = "vh-route-layer"
    private const val WALK_SOURCE_ID = "vh-walk-route-source"
    private const val WALK_LAYER_ID = "vh-walk-route-layer"
    private const val WALK_COLOR = "#687386"
    private const val DATA_NOTE_TAG = "vh_data_freshness_note"
}

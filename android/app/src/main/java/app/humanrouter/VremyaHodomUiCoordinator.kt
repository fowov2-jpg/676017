package app.humanrouter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import app.humanrouter.transit.RealtimeTransitRegistry
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
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
 * One lifecycle owner for all cross-cutting phone UI behavior.
 *
 * This intentionally replaces the previous chain of runtime patch objects. It never reads private
 * MainActivity fields, never calls private methods through reflection and never discovers views by
 * matching visible text. Core routing/search remains owned by MainActivity; this coordinator owns
 * only the journey animation, route-sheet mechanics, honest data disclosure and route-map polish.
 */
internal object VremyaHodomUiCoordinator : Application.ActivityLifecycleCallbacks {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        controllers.getOrPut(activity) { Controller(activity) }.resume()
    }

    override fun onActivityPaused(activity: Activity) {
        (activity as? MainActivity)?.let { controllers[it]?.pause() }
    }

    override fun onActivityDestroyed(activity: Activity) {
        (activity as? MainActivity)?.let { controllers.remove(it)?.destroy() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    internal enum class SheetState { COLLAPSED, MEDIUM, EXPANDED }

    private class Controller(private val activity: MainActivity) {
        private val root: View = activity.findViewById(R.id.root)
        private val routeSheet: View = activity.findViewById(R.id.routeResultsContainer)
        private val routePanel: LinearLayout = activity.findViewById(R.id.routeResultsPanel)
        private val routesTab: TextView = activity.findViewById(R.id.routesNavButton)
        private val mapView: MapView = activity.findViewById(R.id.mapView)
        private val journeyRow: LinearLayout = activity.findViewById(R.id.journeyRow)
        private val journeyImage: ImageView = activity.findViewById(R.id.journeyImage)
        private val journeyStageText: TextView = activity.findViewById(R.id.journeyStageText)
        private val showTransportSwitch: TextView = activity.findViewById(R.id.showTransportSwitch)
        private val handler = Handler(Looper.getMainLooper())

        private var resumed = false
        private var lastRouteId: String? = null
        private var lastStyledRouteSignature: String? = null
        private var sheetState = SheetState.MEDIUM
        private var suppressLayoutRefresh = false

        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!suppressLayoutRefresh) refreshUi(forceMap = false)
        }
        private val liveListener: (TripLiveSnapshot) -> Unit = { snapshot ->
            activity.runOnUiThread {
                ensureRouteUi(snapshot.route)
                updateLiveDisclosure(snapshot)
                styleRouteMap(snapshot.route, force = true)
            }
        }
        private val gpsTicker = object : Runnable {
            override fun run() {
                if (!resumed) return
                currentRoute()?.let(::updateGpsCard)
                handler.postDelayed(this, GPS_REFRESH_MS)
            }
        }

        init {
            installJourneyScene()
            installSheetGesture()
            installRealtimeDisclosure()
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            TripLiveState.addListener(liveListener)
            root.post { refreshUi(forceMap = true) }
        }

        fun resume() {
            resumed = true
            refreshUi(forceMap = true)
            handler.removeCallbacks(gpsTicker)
            handler.post(gpsTicker)
        }

        fun pause() {
            resumed = false
            handler.removeCallbacks(gpsTicker)
        }

        fun destroy() {
            pause()
            TripLiveState.removeListener(liveListener)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            routeSheet.setOnTouchListener(null)
        }

        private fun refreshUi(forceMap: Boolean) {
            val route = currentRoute() ?: return
            if (routesTab.isSelected && routeSheet.visibility == View.VISIBLE) ensureRouteUi(route)
            val changed = route.id != lastRouteId
            if (changed || forceMap) {
                lastRouteId = route.id
                styleRouteMap(route, force = forceMap)
            }
        }

        private fun currentRoute(): RouteCandidate? =
            TripLiveState.current()?.route
                ?: ActiveTripStore.load(activity)?.route
                ?: LastPlanStore.seed?.route

        private fun installJourneyScene() {
            if (journeyRow.findViewWithTag<View>(JOURNEY_SCENE_TAG) != null) return
            journeyImage.visibility = View.GONE
            val params = journeyRow.layoutParams
            params.height = dp(98)
            journeyRow.layoutParams = params

            val scene = JourneySceneView(activity).apply {
                tag = JOURNEY_SCENE_TAG
                contentDescription = JOURNEY_DESCRIPTION
                onStageChanged = { stage ->
                    if (journeyStageText.text != stage.label) journeyStageText.text = stage.label
                }
            }
            journeyRow.addView(
                scene,
                0,
                LinearLayout.LayoutParams(dp(188), dp(94)).apply { rightMargin = dp(8) }
            )
        }

        private fun installRealtimeDisclosure() {
            showTransportSwitch.text = "Показывать линии маршрута"
            val parent = showTransportSwitch.parent as? LinearLayout ?: return
            if (parent.findViewWithTag<View>(REALTIME_DISCLOSURE_TAG) != null) return
            val index = parent.indexOfChild(showTransportSwitch)
            parent.addView(
                TextView(activity).apply {
                    tag = REALTIME_DISCLOSURE_TAG
                    text = RealtimeTransitRegistry.userMessage()
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(activity, R.color.vh_text_tertiary))
                    setPadding(0, 0, 0, dp(8))
                },
                (index + 1).coerceAtMost(parent.childCount),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        private fun installSheetGesture() {
            var downY = 0f
            var startHeight = 0
            routeSheet.setOnTouchListener { view, event ->
                if (view.visibility != View.VISIBLE) return@setOnTouchListener false
                val handleZone = dp(56)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (event.y > handleZone) return@setOnTouchListener false
                        downY = event.rawY
                        startHeight = view.height.takeIf { it > 0 } ?: view.layoutParams.height
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (event.rawY - downY).roundToInt()
                        val heights = sheetHeights()
                        setSheetHeight((startHeight - delta).coerceIn(heights.first(), heights.last()))
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val heights = sheetHeights()
                        val current = view.height
                        val nearestIndex = heights.indices.minByOrNull { index -> kotlin.math.abs(heights[index] - current) } ?: 1
                        sheetState = SheetState.values()[nearestIndex]
                        animateSheetHeight(heights[nearestIndex])
                        true
                    }
                    else -> false
                }
            }
        }

        private fun sheetHeights(): IntArray {
            val rootHeight = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val collapsed = dp(164)
            val medium = max(dp(278), (rootHeight * 0.34f).roundToInt())
            val expanded = max(medium + dp(92), min(dp(560), (rootHeight * 0.56f).roundToInt()))
            return intArrayOf(collapsed, medium, expanded)
        }

        private fun setSheetHeight(height: Int) {
            if (routeSheet.layoutParams.height == height) return
            suppressLayoutRefresh = true
            routeSheet.layoutParams = routeSheet.layoutParams.apply { this.height = height }
            suppressLayoutRefresh = false
        }

        private fun animateSheetHeight(target: Int) {
            val start = routeSheet.height.takeIf { it > 0 } ?: routeSheet.layoutParams.height
            if (start == target) return
            android.animation.ValueAnimator.ofInt(start, target).apply {
                duration = 180L
                addUpdateListener { animation -> setSheetHeight(animation.animatedValue as Int) }
                start()
            }
        }

        private fun ensureRouteUi(route: RouteCandidate) {
            if (routePanel.childCount == 0) return
            hideLegacyDirectRouteStrips()
            ensureUnifiedStrip(route)
            updateGpsCard(route)
            TripLiveState.current()?.let(::updateLiveDisclosure)
        }

        private fun hideLegacyDirectRouteStrips() {
            for (index in 0 until routePanel.childCount) {
                val child = routePanel.getChildAt(index)
                if (child is HorizontalScrollView && child.tag?.toString()?.startsWith(ROUTE_STRIP_PREFIX) != true) {
                    child.visibility = View.GONE
                }
            }
        }

        private fun ensureUnifiedStrip(route: RouteCandidate) {
            val expectedTag = ROUTE_STRIP_PREFIX + route.id
            var existing: View? = null
            val stale = ArrayList<View>()
            for (index in 0 until routePanel.childCount) {
                val child = routePanel.getChildAt(index)
                val tag = child.tag?.toString().orEmpty()
                if (tag == expectedTag) existing = child
                else if (tag.startsWith(ROUTE_STRIP_PREFIX)) stale += child
            }
            stale.forEach(routePanel::removeView)
            if (existing != null) return

            val strip = HorizontalScrollView(activity).apply {
                tag = expectedTag
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                contentDescription = TransitJourneyVisibilityGuard.V2_DESCRIPTION
                setPadding(0, dp(4), 0, dp(7))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    contentDescription = UNIFIED_ROUTE_DESCRIPTION
                    route.legs.forEachIndexed { index, leg ->
                        if (index > 0) addView(separator())
                        addView(routeToken(leg))
                    }
                })
            }
            val insertAt = min(2, routePanel.childCount)
            routePanel.addView(
                strip,
                insertAt,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        private fun separator(): TextView = TextView(activity).apply {
            text = "›"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(ContextCompat.getColor(activity, R.color.vh_text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(44))
        }

        private fun routeToken(leg: RouteLeg): TextView = TextView(activity).apply {
            val visual = TransitVisualCatalog.forLeg(leg)
            text = when (leg.mode) {
                TransportMode.WALK -> if (leg.walkMeters > 0) "Пешком ${formatMeters(leg.walkMeters)}" else "Пешком"
                TransportMode.BUS, TransportMode.TRAM -> visual.badge.ifBlank { visual.label }
                TransportMode.METRO -> "Метро ${visual.badge}".trim()
                TransportMode.MCC -> "МЦК 14"
                TransportMode.MCD -> visual.badge.ifBlank { "МЦД" }
                TransportMode.TRAIN -> visual.badge.ifBlank { "Поезд" }
            }
            gravity = Gravity.CENTER
            minHeight = dp(42)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(visual.color)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.vh_surface_muted)
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
            maxLines = 1
        }

        private fun legLabel(leg: RouteLeg): String {
            val visual = TransitVisualCatalog.forLeg(leg)
            return when (leg.mode) {
                TransportMode.WALK -> "Пешком ${formatMeters(leg.walkMeters)}"
                TransportMode.BUS -> "Автобус ${visual.badge}".trim()
                TransportMode.TRAM -> "Трамвай ${visual.badge}".trim()
                TransportMode.METRO -> "Метро ${visual.badge}".trim()
                TransportMode.MCC -> "МЦК"
                TransportMode.MCD -> "МЦД ${visual.badge}".trim()
                TransportMode.TRAIN -> "Поезд ${visual.badge}".trim()
            }
        }

        private fun formatMeters(meters: Int): String = when {
            meters >= 1000 -> String.format(java.util.Locale.US, "%.1f км", meters / 1000.0)
            else -> "$meters м"
        }

        private fun updateLiveDisclosure(snapshot: TripLiveSnapshot) {
            if (!routesTab.isSelected || routeSheet.visibility != View.VISIBLE) return
            var view = routePanel.findViewWithTag<TextView>(LIVE_STATUS_TAG)
            if (view == null) {
                view = TextView(activity).apply {
                    tag = LIVE_STATUS_TAG
                    textSize = 11.5f
                    setTextColor(ContextCompat.getColor(activity, R.color.vh_text_tertiary))
                    setPadding(0, dp(2), 0, dp(5))
                }
                routePanel.addView(view, 0)
            }
            view.text = buildString {
                if (snapshot.approximate) append("≈ ")
                append("Маршрут обновляется по расписанию и GPS пользователя")
                if (snapshot.status.isNotBlank()) append(" · ").append(snapshot.status)
            }
        }

        private fun updateGpsCard(route: RouteCandidate) {
            if (!routesTab.isSelected || routeSheet.visibility != View.VISIBLE) return
            val location = latestLocation() ?: return
            if (System.currentTimeMillis() - location.time > MAX_LOCATION_AGE_MS) return
            if (location.accuracy > MAX_LOCATION_ACCURACY_M) return
            val point = GeoPoint(location.latitude, location.longitude)
            val nearest = route.legs.mapIndexed { index, leg ->
                val distance = normalizedPoints(leg).minOfOrNull { haversineMeters(point, it) } ?: Double.MAX_VALUE
                Triple(index, leg, distance)
            }.minByOrNull { it.third } ?: return
            val limit = max(220.0, location.accuracy * 3.0)
            if (nearest.third > limit) return

            var view = routePanel.findViewWithTag<TextView>(GPS_STATUS_TAG)
            if (view == null) {
                view = TextView(activity).apply {
                    tag = GPS_STATUS_TAG
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(activity, R.color.vh_primary))
                    background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                }
                routePanel.addView(view, min(1, routePanel.childCount))
            }
            view.text = "GPS · этап ${nearest.first + 1}/${route.legs.size}: ${legLabel(nearest.second)} · точность ≈${location.accuracy.roundToInt()} м"
        }

        private fun hasLocationPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    activity,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        private fun latestLocation(): Location? {
            if (!hasLocationPermission()) return null
            val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return runCatching {
                manager.getProviders(true)
                    .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                    .maxByOrNull(Location::getTime)
            }.getOrNull()
        }

        private fun styleRouteMap(route: RouteCandidate, force: Boolean) {
            val signature = routeSignature(route)
            if (!force && signature == lastStyledRouteSignature) return
            lastStyledRouteSignature = signature
            mapView.getMapAsync { map ->
                map.getStyle { style ->
                    val transit = ArrayList<Feature>()
                    val walking = ArrayList<Feature>()
                    route.legs.forEach { leg ->
                        val points = normalizedPoints(leg)
                        if (points.size < 2) return@forEach
                        val feature = Feature.fromGeometry(
                            LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
                        ).apply {
                            addStringProperty("segment_color", TransitVisualCatalog.colorHex(leg.mode, leg.lineName, leg.lineId))
                            addStringProperty("segment_mode", leg.mode.name)
                        }
                        if (leg.mode == TransportMode.WALK) walking += feature else transit += feature
                    }

                    val transitSource = style.getSourceAs<GeoJsonSource>(TRANSIT_SOURCE_ID)
                        ?: GeoJsonSource(TRANSIT_SOURCE_ID, emptyCollection()).also(style::addSource)
                    transitSource.setGeoJson(FeatureCollection.fromFeatures(transit.toTypedArray()))
                    val transitLayer = style.getLayerAs<LineLayer>(TRANSIT_LAYER_ID)
                        ?: LineLayer(TRANSIT_LAYER_ID, TRANSIT_SOURCE_ID).also(style::addLayer)
                    transitLayer.setProperties(
                        PropertyFactory.lineColor(Expression.get("segment_color")),
                        PropertyFactory.lineWidth(7.0f),
                        PropertyFactory.lineOpacity(0.96f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.visibility(Property.VISIBLE)
                    )

                    val walkSource = style.getSourceAs<GeoJsonSource>(WALK_SOURCE_ID)
                        ?: GeoJsonSource(WALK_SOURCE_ID, emptyCollection()).also(style::addSource)
                    walkSource.setGeoJson(FeatureCollection.fromFeatures(walking.toTypedArray()))
                    val walkLayer = style.getLayerAs<LineLayer>(WALK_LAYER_ID)
                        ?: LineLayer(WALK_LAYER_ID, WALK_SOURCE_ID).also(style::addLayer)
                    walkLayer.setProperties(
                        PropertyFactory.lineColor(WALK_COLOR),
                        PropertyFactory.lineWidth(4.4f),
                        PropertyFactory.lineOpacity(0.95f),
                        PropertyFactory.lineDasharray(arrayOf(1.1f, 1.6f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.visibility(Property.VISIBLE)
                    )

                    installStopLayer(style, route)
                    style.getLayerAs<LineLayer>(LEGACY_ROUTE_LAYER_ID)?.setProperties(
                        PropertyFactory.lineOpacity(0.10f)
                    )
                }
            }
        }

        private fun installStopLayer(style: org.maplibre.android.maps.Style, route: RouteCandidate) {
            val origin = route.legs.firstOrNull()?.from?.point
            val destination = route.legs.lastOrNull()?.to?.point
            val features = LinkedHashMap<String, Feature>()
            route.legs.filter { it.mode != TransportMode.WALK }.forEach { leg ->
                val color = TransitVisualCatalog.colorHex(leg.mode, leg.lineName, leg.lineId)
                listOf(leg.from.point, leg.to.point).forEach { point ->
                    if (!samePoint(point, origin) && !samePoint(point, destination)) {
                        val key = "${point.lat.format6()}:${point.lon.format6()}"
                        features[key] = Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).apply {
                            addStringProperty("stop_color", color)
                        }
                    }
                }
            }
            val source = style.getSourceAs<GeoJsonSource>(STOP_SOURCE_ID)
                ?: GeoJsonSource(STOP_SOURCE_ID, emptyCollection()).also(style::addSource)
            source.setGeoJson(FeatureCollection.fromFeatures(features.values.toTypedArray()))
            val layer = style.getLayerAs<CircleLayer>(STOP_LAYER_ID)
                ?: CircleLayer(STOP_LAYER_ID, STOP_SOURCE_ID).also(style::addLayer)
            layer.setProperties(
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeColor(Expression.get("stop_color")),
                PropertyFactory.circleStrokeWidth(2.6f),
                PropertyFactory.circleRadius(5.2f),
                PropertyFactory.circleOpacity(0.98f),
                PropertyFactory.visibility(Property.VISIBLE)
            )
        }

        private fun routeSignature(route: RouteCandidate): String = buildString {
            append(route.id)
            route.legs.forEach { leg ->
                append('|').append(leg.mode).append(':').append(leg.lineId).append(':').append(leg.lineName)
                val points = normalizedPoints(leg)
                append(':').append(points.firstOrNull()?.lat).append(',').append(points.firstOrNull()?.lon)
                append('>').append(points.lastOrNull()?.lat).append(',').append(points.lastOrNull()?.lon)
            }
        }

        private fun normalizedPoints(leg: RouteLeg): List<GeoPoint> {
            val points = leg.mapPoints().toMutableList()
            if (points.isEmpty()) return listOf(leg.from.point, leg.to.point)
            if (!samePoint(points.first(), leg.from.point)) points.add(0, leg.from.point)
            if (!samePoint(points.last(), leg.to.point)) points.add(leg.to.point)
            return points
        }

        private fun samePoint(a: GeoPoint?, b: GeoPoint?): Boolean {
            if (a == null || b == null) return false
            return haversineMeters(a, b) < 2.0
        }

        private fun Double.format6(): String = String.format(java.util.Locale.US, "%.6f", this)
        private fun emptyCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyArray<Feature>())
        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val q = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * 6_371_000.0 * asin(min(1.0, sqrt(q)))
    }

    private const val JOURNEY_SCENE_TAG = "vh_unified_journey_scene"
    private const val JOURNEY_DESCRIPTION = "Анимация поездки: человек, остановка, автобус, метро и поезд"
    private const val REALTIME_DISCLOSURE_TAG = "vh_realtime_disclosure"
    private const val ROUTE_STRIP_PREFIX = "vh_unified_route_strip:"
    private const val UNIFIED_ROUTE_DESCRIPTION = "Этапы маршрута по видам транспорта"
    private const val LIVE_STATUS_TAG = "vh_unified_live_status"
    private const val GPS_STATUS_TAG = "vh_unified_gps_status"
    private const val GPS_REFRESH_MS = 10_000L
    private const val MAX_LOCATION_AGE_MS = 2 * 60_000L
    private const val MAX_LOCATION_ACCURACY_M = 200f

    private const val TRANSIT_SOURCE_ID = "vh-unified-transit-source"
    private const val TRANSIT_LAYER_ID = "vh-unified-transit-layer"
    private const val WALK_SOURCE_ID = "vh-unified-walk-source"
    private const val WALK_LAYER_ID = "vh-unified-walk-layer"
    private const val STOP_SOURCE_ID = "vh-unified-stop-source"
    private const val STOP_LAYER_ID = "vh-unified-stop-layer"
    private const val LEGACY_ROUTE_LAYER_ID = "vh-route-layer"
    private const val WALK_COLOR = "#687386"
}

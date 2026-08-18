package app.humanrouter

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.WeakHashMap

/**
 * Active-trip map owner for passenger position and current-leg framing.
 *
 * It never changes route choice or transport palette. The current GPS sample is rendered through a
 * dedicated marker source, and the camera is fitted once when the passenger enters a new route leg.
 * Subsequent samples only move the marker, so manual map panning remains user-owned until the next
 * real stage transition (or the explicit current-location control is used).
 */
internal object ActiveTripMapProgressOwner {
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
        private val mapView = activity.findViewById<MapView>(R.id.mapView)
        private val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        private val primary = activity.findViewById<Button>(R.id.routePrimaryAction)
        private var destroyed = false
        private var lastCameraKey: String? = null
        private var reconcilePosted = false

        private val progressListener: (TripProgressSnapshot) -> Unit = { snapshot ->
            if (!destroyed) activity.runOnUiThread { render(snapshot) }
        }
        private val liveListener: (TripLiveSnapshot) -> Unit = {
            if (!destroyed) activity.runOnUiThread(::scheduleReconcile)
        }
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleReconcile()
        }

        init {
            TripProgressState.addListener(progressListener)
            TripLiveState.addListener(liveListener)
            root.addOnLayoutChangeListener(layoutListener)
            sheet.addOnLayoutChangeListener(layoutListener)
            scheduleReconcile()
        }

        fun destroy() {
            destroyed = true
            TripProgressState.removeListener(progressListener)
            TripLiveState.removeListener(liveListener)
            root.removeOnLayoutChangeListener(layoutListener)
            sheet.removeOnLayoutChangeListener(layoutListener)
        }

        private fun scheduleReconcile() {
            if (destroyed || reconcilePosted) return
            reconcilePosted = true
            root.post {
                reconcilePosted = false
                val snapshot = TripProgressState.current()
                if (snapshot != null && isActiveTrip()) render(snapshot) else hideMarker()
            }
        }

        private fun isActiveTrip(): Boolean =
            sheet.visibility == View.VISIBLE &&
                primary.visibility == View.VISIBLE &&
                primary.text?.toString()?.contains("Заверш", ignoreCase = true) == true

        private fun render(snapshot: TripProgressSnapshot) {
            if (destroyed || activity.isFinishing || activity.isDestroyed || !isActiveTrip()) {
                hideMarker()
                return
            }
            val route = currentRoute()?.takeIf { it.id == snapshot.routeId } ?: return
            val leg = route.legs.getOrNull(snapshot.legIndex) ?: return
            val visual = TransitVisualCatalog.forLeg(leg)
            mapView.getMapAsync { map ->
                if (destroyed || !isActiveTrip()) return@getMapAsync
                map.getStyle { style ->
                    val feature = Feature.fromGeometry(Point.fromLngLat(snapshot.point.lon, snapshot.point.lat))
                    val collection = FeatureCollection.fromFeature(feature)
                    val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
                        ?: GeoJsonSource(SOURCE_ID, collection).also(style::addSource)
                    source.setGeoJson(collection)

                    val halo = style.getLayerAs<CircleLayer>(HALO_LAYER_ID)
                        ?: CircleLayer(HALO_LAYER_ID, SOURCE_ID).also(style::addLayer)
                    halo.setProperties(
                        PropertyFactory.circleRadius(16f),
                        PropertyFactory.circleColor(Color.WHITE),
                        PropertyFactory.circleOpacity(0.94f),
                        PropertyFactory.circleStrokeColor(visual.color),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.visibility(Property.VISIBLE)
                    )

                    val marker = style.getLayerAs<CircleLayer>(MARKER_LAYER_ID)
                        ?: CircleLayer(MARKER_LAYER_ID, SOURCE_ID).also(style::addLayer)
                    marker.setProperties(
                        PropertyFactory.circleRadius(9.5f),
                        PropertyFactory.circleColor(visual.color),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleOpacity(1f),
                        PropertyFactory.visibility(Property.VISIBLE)
                    )
                }

                val cameraKey = "${route.id}:${snapshot.legIndex}"
                if (cameraKey != lastCameraKey) {
                    lastCameraKey = cameraKey
                    focusCurrentLeg(map, leg.mapPoints(), snapshot.point)
                }
            }
        }

        private fun focusCurrentLeg(
            map: org.maplibre.android.maps.MapLibreMap,
            routePoints: List<app.humanrouter.routing.GeoPoint>,
            passengerPoint: app.humanrouter.routing.GeoPoint
        ) {
            val points = buildList {
                add(passengerPoint)
                routePoints.forEach { if (it != lastOrNull()) add(it) }
            }
            if (points.size < 2) return
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(LatLng(it.lat, it.lon)) }
            val top = root.findViewWithTag<View>("reference_active_trip_top")
            val topPadding = ((top?.bottom ?: 0) + dp(18)).coerceAtMost(root.height / 2)
            val sheetTop = (sheet.top + sheet.translationY).toInt()
            val bottomPadding = (root.height - sheetTop + dp(22)).coerceAtMost(root.height / 2)
            runCatching {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        builder.build(),
                        dp(42),
                        topPadding,
                        dp(42),
                        bottomPadding
                    )
                )
            }
        }

        private fun hideMarker() {
            lastCameraKey = null
            mapView.getMapAsync { map ->
                map.getStyle { style ->
                    style.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(
                        FeatureCollection.fromFeatures(emptyArray<Feature>())
                    )
                    style.getLayerAs<CircleLayer>(HALO_LAYER_ID)?.setProperties(
                        PropertyFactory.visibility(Property.NONE)
                    )
                    style.getLayerAs<CircleLayer>(MARKER_LAYER_ID)?.setProperties(
                        PropertyFactory.visibility(Property.NONE)
                    )
                }
            }
        }

        private fun currentRoute(): app.humanrouter.routing.RouteCandidate? =
            TripLiveState.current()?.route
                ?: ActiveTripStore.load(activity)?.route
                ?: LastPlanStore.seed?.route

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }

    private const val SOURCE_ID = "vh-active-trip-position-source"
    private const val HALO_LAYER_ID = "vh-active-trip-position-halo"
    private const val MARKER_LAYER_ID = "vh-active-trip-position-marker"
}

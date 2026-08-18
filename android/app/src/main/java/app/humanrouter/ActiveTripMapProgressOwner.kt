package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import app.humanrouter.routing.LastPlanStore
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
            styleActiveBadge(leg, visual)
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
                        PropertyFactory.circleRadius(18f),
                        PropertyFactory.circleColor(Color.WHITE),
                        PropertyFactory.circleOpacity(0.94f),
                        PropertyFactory.circleStrokeColor(visual.color),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.visibility(Property.VISIBLE)
                    )

                    val markerImageId = markerImageId(leg, visual)
                    if (style.getImage(markerImageId) == null) {
                        style.addImage(markerImageId, markerBitmap(visual))
                    }
                    val marker = style.getLayerAs<SymbolLayer>(MARKER_LAYER_ID)
                        ?: SymbolLayer(MARKER_LAYER_ID, SOURCE_ID).also(style::addLayer)
                    marker.setProperties(
                        PropertyFactory.iconImage(markerImageId),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconSize(0.82f),
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

        /**
         * The top active-trip badge and the map passenger marker must describe the same transport.
         * ResponsiveProductUi creates the card, while TransitVisualCatalog is the single palette and
         * line-semantics source used by the route map. Re-applying only these presentation properties
         * keeps the card stable while preventing a generic red metro badge next to an orange line 6.
         */
        private fun styleActiveBadge(
            leg: app.humanrouter.routing.RouteLeg,
            visual: TransitVisualCatalog.Visual
        ) {
            val top = root.findViewWithTag<ViewGroup>(ACTIVE_TOP_TAG) ?: return
            val badge = descendantTextViews(top).firstOrNull() ?: return
            val label = if (leg.mode == app.humanrouter.routing.TransportMode.WALK) {
                "ПЕШ"
            } else {
                visual.badge.ifBlank { visual.label }
            }
            if (badge.text?.toString() != label) badge.text = label
            badge.backgroundTintList = ColorStateList.valueOf(visual.color)
            badge.setTextColor(visual.foreground)
            badge.compoundDrawableTintList = ColorStateList.valueOf(visual.foreground)
        }

        private fun descendantTextViews(view: View): Sequence<TextView> = sequence {
            if (view is TextView) yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    yieldAll(descendantTextViews(view.getChildAt(index)))
                }
            }
        }

        private fun markerImageId(
            leg: app.humanrouter.routing.RouteLeg,
            visual: TransitVisualCatalog.Visual
        ): String = "$MARKER_IMAGE_PREFIX-${leg.mode.name.lowercase()}-${visual.color}"

        private fun markerBitmap(visual: TransitVisualCatalog.Visual): Bitmap {
            val size = dp(48)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val marker = TransitGlyphView(
                context = activity,
                glyph = visual.glyph,
                fillColor = visual.color,
                foregroundColor = visual.foreground
            )
            val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            marker.measure(exact, exact)
            marker.layout(0, 0, size, size)
            marker.draw(Canvas(bitmap))
            return bitmap
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
            val top = root.findViewWithTag<View>(ACTIVE_TOP_TAG)
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
                    style.getLayerAs<SymbolLayer>(MARKER_LAYER_ID)?.setProperties(
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

    private const val ACTIVE_TOP_TAG = "reference_active_trip_top"
    private const val SOURCE_ID = "vh-active-trip-position-source"
    private const val HALO_LAYER_ID = "vh-active-trip-position-halo"
    private const val MARKER_LAYER_ID = "vh-active-trip-position-marker"
    private const val MARKER_IMAGE_PREFIX = "vh-active-trip-position-image"
}

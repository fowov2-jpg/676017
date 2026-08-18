package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import app.humanrouter.routing.LastPlanStore
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.WeakHashMap

/**
 * Active-trip map owner for passenger position and current-leg framing.
 *
 * It never changes route choice or transport palette. The passenger marker is an Android overlay
 * positioned from MapLibre projection so the approved TransitGlyphView is rendered exactly as the
 * rest of the product's transport visual system. The camera is fitted once when the passenger enters
 * a new route leg; subsequent samples only move the marker, so manual map panning remains user-owned
 * until the next real stage transition (or the explicit current-location control is used).
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
        private var activeMap: MapLibreMap? = null
        private var passengerPoint: app.humanrouter.routing.GeoPoint? = null
        private var markerOverlay: FrameLayout? = null
        private var markerVisualKey: String? = null

        private val progressListener: (TripProgressSnapshot) -> Unit = { snapshot ->
            if (!destroyed) activity.runOnUiThread { render(snapshot) }
        }
        private val liveListener: (TripLiveSnapshot) -> Unit = {
            if (!destroyed) activity.runOnUiThread(::scheduleReconcile)
        }
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleReconcile()
        }
        private val cameraMoveListener = MapLibreMap.OnCameraMoveListener {
            val map = activeMap ?: return@OnCameraMoveListener
            val point = passengerPoint ?: return@OnCameraMoveListener
            positionMarker(map, point)
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
            activeMap?.removeOnCameraMoveListener(cameraMoveListener)
            activeMap = null
            passengerPoint = null
            markerOverlay?.let(root::removeView)
            markerOverlay = null
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
            passengerPoint = snapshot.point
            mapView.getMapAsync { map ->
                if (destroyed || !isActiveTrip()) return@getMapAsync
                attachMap(map)
                ensureMarkerOverlay(visual)
                positionMarker(map, snapshot.point)

                val cameraKey = "${route.id}:${snapshot.legIndex}"
                if (cameraKey != lastCameraKey) {
                    lastCameraKey = cameraKey
                    focusCurrentLeg(map, leg.mapPoints(), snapshot.point)
                }
            }
        }

        private fun attachMap(map: MapLibreMap) {
            if (activeMap === map) return
            activeMap?.removeOnCameraMoveListener(cameraMoveListener)
            activeMap = map
            map.addOnCameraMoveListener(cameraMoveListener)
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

        private fun ensureMarkerOverlay(visual: TransitVisualCatalog.Visual) {
            val key = "${visual.glyph}:${visual.color}:${visual.foreground}"
            val existing = markerOverlay
            if (existing != null && markerVisualKey == key) {
                existing.visibility = View.VISIBLE
                return
            }
            existing?.let(root::removeView)

            val size = dp(56)
            val marker = FrameLayout(activity).apply {
                tag = MARKER_OVERLAY_TAG
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = dp(18).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(3), visual.color)
                }
                addView(
                    TransitGlyphView(
                        context = activity,
                        glyph = visual.glyph,
                        fillColor = visual.color,
                        foregroundColor = visual.foreground
                    ),
                    FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
                )
            }
            val params = FrameLayout.LayoutParams(size, size)
            val index = (root.indexOfChild(mapView) + 1).coerceIn(0, root.childCount)
            root.addView(marker, index, params)
            markerOverlay = marker
            markerVisualKey = key
        }

        private fun positionMarker(map: MapLibreMap, point: app.humanrouter.routing.GeoPoint) {
            val marker = markerOverlay ?: return
            if (marker.visibility != View.VISIBLE) marker.visibility = View.VISIBLE
            val pixel = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
            val mapLocation = IntArray(2)
            val rootLocation = IntArray(2)
            mapView.getLocationOnScreen(mapLocation)
            root.getLocationOnScreen(rootLocation)
            val size = marker.layoutParams.width.takeIf { it > 0 } ?: dp(56)
            val mapLeft = mapLocation[0] - rootLocation[0]
            val mapTop = mapLocation[1] - rootLocation[1]
            val mapRight = mapLeft + mapView.width
            val mapBottom = mapTop + mapView.height
            val gap = dp(8).toFloat()
            val topCardBottom = root.findViewWithTag<View>(ACTIVE_TOP_TAG)
                ?.let { it.bottom + it.translationY + gap }
                ?: mapTop.toFloat()
            val sheetTop = if (sheet.visibility == View.VISIBLE) {
                sheet.top + sheet.translationY
            } else {
                mapBottom.toFloat()
            }
            val minX = mapLeft.toFloat()
            val maxX = (mapRight - size).toFloat().coerceAtLeast(minX)
            val minY = maxOf(mapTop.toFloat(), topCardBottom)
            val maxY = (minOf(mapBottom.toFloat(), sheetTop) - size - gap).coerceAtLeast(minY)
            val projectedX = mapLeft + pixel.x - size / 2f
            val projectedY = mapTop + pixel.y - size / 2f
            marker.x = projectedX.coerceIn(minX, maxX)
            marker.y = projectedY.coerceIn(minY, maxY)
        }

        private fun focusCurrentLeg(
            map: MapLibreMap,
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
            // The passenger pin is 56dp high. Keep enough bottom camera padding for the complete pin
            // plus a small visual gap above the journey sheet, not merely the route center point.
            val bottomPadding = (root.height - sheetTop + dp(72)).coerceAtMost(root.height / 2)
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
            // moveCamera may synchronously emit projection changes, but a final post keeps the marker
            // aligned even on implementations where the camera callback is deferred to the next frame.
            root.post { positionMarker(map, passengerPoint) }
        }

        private fun hideMarker() {
            lastCameraKey = null
            passengerPoint = null
            markerOverlay?.visibility = View.GONE
        }

        private fun currentRoute(): app.humanrouter.routing.RouteCandidate? =
            TripLiveState.current()?.route
                ?: ActiveTripStore.load(activity)?.route
                ?: LastPlanStore.seed?.route

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }

    private const val ACTIVE_TOP_TAG = "reference_active_trip_top"
    private const val MARKER_OVERLAY_TAG = "vh_active_trip_passenger_marker"
}

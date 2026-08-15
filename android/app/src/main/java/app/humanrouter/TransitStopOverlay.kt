package app.humanrouter

import android.graphics.Color
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.TransportMode
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.WeakHashMap

/** Adds reference-style outlined stop markers for every public-transport leg on the map. */
internal object TransitStopOverlay {
    private val installed = WeakHashMap<MainActivity, Boolean>()

    @Synchronized
    fun install(activity: MainActivity) {
        installed[activity] = true
        refresh(activity)
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        installed.remove(activity)
    }

    fun refresh(activity: MainActivity) {
        if (installed[activity] != true || activity.isFinishing || activity.isDestroyed) return
        val route = currentRoute(activity) ?: return
        val mapView = activity.findViewById<org.maplibre.android.maps.MapView>(R.id.mapView)
        mapView.getMapAsync { map ->
            map.getStyle { style ->
                val features = stopFeatures(route)
                val collection = FeatureCollection.fromFeatures(features.toTypedArray())
                val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
                if (source == null) {
                    style.addSource(GeoJsonSource(SOURCE_ID, collection))
                } else {
                    source.setGeoJson(collection)
                }

                val layer = style.getLayerAs<CircleLayer>(LAYER_ID)
                    ?: CircleLayer(LAYER_ID, SOURCE_ID).also(style::addLayer)
                layer.setProperties(
                    PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleStrokeColor(Expression.get("stop_color")),
                    PropertyFactory.circleStrokeWidth(2.6f),
                    PropertyFactory.circleRadius(5.6f),
                    PropertyFactory.circleOpacity(0.98f),
                    PropertyFactory.visibility(Property.VISIBLE)
                )
            }
        }
    }

    private fun currentRoute(activity: MainActivity): RouteCandidate? =
        TripLiveState.current()?.route
            ?: ActiveTripStore.load(activity)?.route
            ?: LastPlanStore.seed?.route

    private fun stopFeatures(route: RouteCandidate): List<Feature> {
        val result = LinkedHashMap<String, Feature>()
        route.legs.forEach { leg ->
            if (leg.mode == TransportMode.WALK) return@forEach
            val color = TransitVisualCatalog.colorHex(leg.mode, leg.lineName, leg.lineId)
            listOf(leg.from to "board", leg.to to "alight").forEach { (place, role) ->
                val key = "${place.point.lat}:${place.point.lon}:${leg.mode}:${leg.lineName ?: leg.lineId}"
                result[key] = Feature.fromGeometry(Point.fromLngLat(place.point.lon, place.point.lat)).apply {
                    addStringProperty("stop_color", color)
                    addStringProperty("stop_mode", leg.mode.name)
                    addStringProperty("stop_name", place.name)
                    addStringProperty("stop_role", role)
                }
            }
        }
        return result.values.toList()
    }

    private const val SOURCE_ID = "vh-reference-stop-source"
    private const val LAYER_ID = "vh-reference-stop-layer"
}

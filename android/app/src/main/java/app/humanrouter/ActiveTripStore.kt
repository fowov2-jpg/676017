package app.humanrouter

import android.content.Context
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.RoutePlace
import app.humanrouter.routing.TransportMode
import org.json.JSONArray
import org.json.JSONObject

internal data class ActiveTripSnapshot(
    val route: RouteCandidate,
    val originTitle: String,
    val originSubtitle: String,
    val destinationTitle: String,
    val destinationSubtitle: String
)

/** Persists the complete visible trip so Activity/process recreation does not lose navigation UI. */
internal object ActiveTripStore {
    private const val PREFS = "active_trip_ui"
    private const val KEY_SNAPSHOT = "snapshot"

    fun save(context: Context, snapshot: ActiveTripSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, encode(snapshot))
            .apply()
    }

    fun load(context: Context): ActiveTripSnapshot? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null)
            ?: return null
        return runCatching { decode(text) }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    internal fun encode(snapshot: ActiveTripSnapshot): String = JSONObject()
        .put("schema", 1)
        .put("origin_title", snapshot.originTitle)
        .put("origin_subtitle", snapshot.originSubtitle)
        .put("destination_title", snapshot.destinationTitle)
        .put("destination_subtitle", snapshot.destinationSubtitle)
        .put("route", encodeRoute(snapshot.route))
        .toString()

    internal fun decode(text: String): ActiveTripSnapshot {
        val root = JSONObject(text)
        require(root.getInt("schema") == 1)
        return ActiveTripSnapshot(
            route = decodeRoute(root.getJSONObject("route")),
            originTitle = root.getString("origin_title"),
            originSubtitle = root.optString("origin_subtitle"),
            destinationTitle = root.getString("destination_title"),
            destinationSubtitle = root.optString("destination_subtitle")
        )
    }

    private fun encodeRoute(route: RouteCandidate): JSONObject = JSONObject()
        .put("id", route.id)
        .put("requested_departure", route.requestedDepartureEpochSec)
        .put("legs", JSONArray().apply { route.legs.forEach { put(encodeLeg(it)) } })

    private fun decodeRoute(root: JSONObject): RouteCandidate {
        val items = root.getJSONArray("legs")
        val legs = ArrayList<RouteLeg>(items.length())
        for (index in 0 until items.length()) legs += decodeLeg(items.getJSONObject(index))
        return RouteCandidate(root.getString("id"), root.getLong("requested_departure"), legs)
    }

    private fun encodeLeg(leg: RouteLeg): JSONObject = JSONObject()
        .put("mode", leg.mode.name)
        .put("from", encodePlace(leg.from))
        .put("to", encodePlace(leg.to))
        .put("departure", leg.departureEpochSec)
        .put("arrival", leg.arrivalEpochSec)
        .put("line_id", leg.lineId)
        .put("line_name", leg.lineName)
        .put("wait", leg.waitSeconds)
        .put("walk", leg.walkMeters)
        .put("uncertainty", leg.uncertaintySeconds)
        .put("confidence", leg.realtimeConfidence)
        .put("transfer_buffer", leg.transferBufferSeconds)
        .put("stops", leg.stopCount)
        .put("geometry", JSONArray().apply {
            leg.geometry.forEach { point -> put(JSONArray().put(point.lat).put(point.lon)) }
        })

    private fun decodeLeg(root: JSONObject): RouteLeg = RouteLeg(
        mode = TransportMode.fromRuntimeValue(root.getString("mode"))
            ?: error("Unknown stored trip mode"),
        from = decodePlace(root.getJSONObject("from")),
        to = decodePlace(root.getJSONObject("to")),
        departureEpochSec = root.getLong("departure"),
        arrivalEpochSec = root.getLong("arrival"),
        lineId = root.optString("line_id").takeIf(String::isNotBlank),
        lineName = root.optString("line_name").takeIf(String::isNotBlank),
        waitSeconds = root.optInt("wait"),
        walkMeters = root.optInt("walk"),
        uncertaintySeconds = root.optInt("uncertainty"),
        realtimeConfidence = root.optDouble("confidence", 0.5),
        transferBufferSeconds = root.optInt("transfer_buffer"),
        stopCount = root.optInt("stops"),
        geometry = decodeGeometry(root.optJSONArray("geometry"))
    )

    private fun decodeGeometry(items: JSONArray?): List<GeoPoint> {
        if (items == null) return emptyList()
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val values = items.optJSONArray(index) ?: continue
                if (values.length() < 2) continue
                val lat = values.optDouble(0, Double.NaN)
                val lon = values.optDouble(1, Double.NaN)
                if (lat.isFinite() && lon.isFinite()) add(GeoPoint(lat, lon))
            }
        }
    }

    private fun encodePlace(place: RoutePlace): JSONObject = JSONObject()
        .put("id", place.id)
        .put("name", place.name)
        .put("lat", place.point.lat)
        .put("lon", place.point.lon)

    private fun decodePlace(root: JSONObject): RoutePlace = RoutePlace(
        id = root.getString("id"),
        name = root.getString("name"),
        point = GeoPoint(root.getDouble("lat"), root.getDouble("lon"))
    )
}

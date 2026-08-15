package app.humanrouter

import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.RoutePlace
import app.humanrouter.routing.TransportMode
import org.json.JSONArray
import org.json.JSONObject

/** Small, versioned codec used for pending replans and cross-component trip state. */
internal object RouteJsonCodec {
    fun encode(route: RouteCandidate): String = JSONObject()
        .put("schema", 1)
        .put("id", route.id)
        .put("requested_departure", route.requestedDepartureEpochSec)
        .put("legs", JSONArray().apply { route.legs.forEach { put(encodeLeg(it)) } })
        .toString()

    fun decode(text: String): RouteCandidate {
        val root = JSONObject(text)
        require(root.getInt("schema") == 1) { "Unsupported route snapshot schema" }
        val items = root.getJSONArray("legs")
        val legs = ArrayList<RouteLeg>(items.length())
        for (index in 0 until items.length()) legs += decodeLeg(items.getJSONObject(index))
        return RouteCandidate(
            id = root.getString("id"),
            requestedDepartureEpochSec = root.getLong("requested_departure"),
            legs = legs
        )
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
            ?: error("Unknown route mode"),
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
}

internal object PendingReplanStore {
    private const val PREFS = "pending_replan"
    private const val KEY_ROUTE = "route"

    fun save(context: android.content.Context, route: RouteCandidate) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_ROUTE, RouteJsonCodec.encode(route)).apply()
    }

    fun load(context: android.content.Context): RouteCandidate? {
        val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_ROUTE, null) ?: return null
        return runCatching { RouteJsonCodec.decode(raw) }.getOrNull()
    }

    fun clear(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }
}

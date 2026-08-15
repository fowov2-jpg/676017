package app.humanrouter

import android.content.SharedPreferences
import app.humanrouter.routing.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

internal data class FavoriteRoute(
    val id: String,
    val originTitle: String,
    val originSubtitle: String,
    val origin: GeoPoint,
    val destinationTitle: String,
    val destinationSubtitle: String,
    val destination: GeoPoint
)

internal object FavoriteRoutesStore {
    private const val KEY = "favorite_routes_v1"
    private const val LIMIT = 12

    fun load(preferences: SharedPreferences): List<FavoriteRoute> =
        decode(preferences.getString(KEY, null).orEmpty())

    fun save(preferences: SharedPreferences, route: FavoriteRoute) {
        val updated = buildList {
            add(route)
            addAll(load(preferences).filterNot { it.id == route.id })
        }.take(LIMIT)
        preferences.edit().putString(KEY, encode(updated)).apply()
    }

    fun remove(preferences: SharedPreferences, id: String) {
        preferences.edit().putString(KEY, encode(load(preferences).filterNot { it.id == id })).apply()
    }

    fun stableId(origin: GeoPoint, destination: GeoPoint): String = listOf(
        origin.lat,
        origin.lon,
        destination.lat,
        destination.lon
    ).joinToString(":") { "%.5f".format(java.util.Locale.US, it) }

    internal fun encode(routes: List<FavoriteRoute>): String = JSONArray().apply {
        routes.forEach { route ->
            put(JSONObject().apply {
                put("id", route.id)
                put("origin_title", route.originTitle)
                put("origin_subtitle", route.originSubtitle)
                put("origin_lat", route.origin.lat)
                put("origin_lon", route.origin.lon)
                put("destination_title", route.destinationTitle)
                put("destination_subtitle", route.destinationSubtitle)
                put("destination_lat", route.destination.lat)
                put("destination_lon", route.destination.lon)
            })
        }
    }.toString()

    internal fun decode(raw: String): List<FavoriteRoute> = runCatching {
        if (raw.isBlank()) return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until minOf(array.length(), LIMIT)) {
                val item = array.getJSONObject(index)
                val origin = GeoPoint(item.getDouble("origin_lat"), item.getDouble("origin_lon"))
                val destination = GeoPoint(item.getDouble("destination_lat"), item.getDouble("destination_lon"))
                add(
                    FavoriteRoute(
                        id = item.optString("id").ifBlank { stableId(origin, destination) },
                        originTitle = item.getString("origin_title"),
                        originSubtitle = item.optString("origin_subtitle"),
                        origin = origin,
                        destinationTitle = item.getString("destination_title"),
                        destinationSubtitle = item.optString("destination_subtitle"),
                        destination = destination
                    )
                )
            }
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}

package app.humanrouter.search

import app.humanrouter.routing.GeoPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class SearchPlace(
    val title: String,
    val subtitle: String,
    val point: GeoPoint
)

internal object PhotonGeocoder {
    fun search(query: String, focus: GeoPoint? = null, limit: Int = 6): List<SearchPlace> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val params = ArrayList<String>()
        params += "q=${enc(q)}"
        params += "lang=ru"
        params += "limit=${limit.coerceIn(1, 10)}"
        params += "bbox=36.75,55.45,38.35,56.05"
        focus?.let {
            params += "lat=${it.lat}"
            params += "lon=${it.lon}"
            params += "zoom=13"
        }

        val connection = (URL("https://photon.komoot.io/api?${params.joinToString("&")}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 7_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/geo+json, application/json")
            setRequestProperty("Accept-Language", "ru")
            setRequestProperty("User-Agent", "HumanRouter-Android/0.1")
        }

        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            parse(json)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): List<SearchPlace> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val result = ArrayList<SearchPlace>(features.length())
        val seen = HashSet<String>()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue

            val p = feature.optJSONObject("properties") ?: JSONObject()
            val name = p.optString("name").ifBlank {
                p.optString("street").ifBlank { p.optString("district").ifBlank { "Точка на карте" } }
            }
            val details = listOf(
                listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" "),
                p.optString("district"),
                p.optString("city"),
                p.optString("state")
            ).filter { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
                .distinct()
                .take(3)
                .joinToString(", ")

            val key = "${"%.5f".format(lat)}:${"%.5f".format(lon)}:$name"
            if (seen.add(key)) result += SearchPlace(name, details, GeoPoint(lat, lon))
        }
        return result
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

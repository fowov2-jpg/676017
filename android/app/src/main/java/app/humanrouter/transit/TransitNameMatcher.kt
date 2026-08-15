package app.humanrouter.transit

import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.TransportMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class TransitPlaceSource(val priority: Int) {
    RAIL_GRAPH(0),
    RAIL_TIMETABLE(1),
    SURFACE(2)
}

internal data class IndexedTransitPlace(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val modes: Set<TransportMode>,
    val source: TransitPlaceSource
)

internal object TransitNameMatcher {
    fun search(
        query: String,
        places: List<IndexedTransitPlace>,
        focus: GeoPoint?,
        limit: Int
    ): List<IndexedTransitPlace> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.length < 2) return emptyList()
        val ranked = places.asSequence()
            .mapNotNull { place ->
                val normalizedName = normalize(place.name)
                val match = matchRank(normalizedQuery, normalizedName) ?: return@mapNotNull null
                RankedPlace(
                    place = place,
                    normalizedName = normalizedName,
                    matchRank = match,
                    distanceMeters = focus?.let { distanceMeters(it, place.point) } ?: Int.MAX_VALUE
                )
            }
            .sortedWith(
                compareBy<RankedPlace> { it.matchRank }
                    .thenBy { it.place.source.priority }
                    .thenBy { it.distanceMeters }
                    .thenBy { it.place.name.lowercase(Locale.ROOT) }
            )
            .toList()
        val merged = LinkedHashMap<String, IndexedTransitPlace>()
        ranked.forEach { item ->
            val existing = merged[item.normalizedName]
            merged[item.normalizedName] = if (existing == null) {
                item.place
            } else {
                existing.copy(modes = existing.modes + item.place.modes)
            }
        }
        return merged.values.take(limit.coerceIn(1, 12))
    }

    internal fun normalize(value: String): String = TOKEN.findAll(
        value.lowercase(Locale.ROOT).replace('ё', 'е')
    ).map(MatchResult::value)
        .filterNot(IGNORED_WORDS::contains)
        .joinToString(" ")

    private fun matchRank(query: String, name: String): Int? = when {
        name == query -> 0
        name.startsWith(query) -> 1
        name.split(' ').any { it.startsWith(query) } -> 2
        query.split(' ').all { token -> name.split(' ').any { it.startsWith(token) } } -> 3
        name.contains(query) -> 4
        else -> null
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Int {
        val latitude = (a.lat + b.lat) * 0.5 * PI / 180.0
        val x = (b.lon - a.lon) * cos(latitude)
        val y = b.lat - a.lat
        return (sqrt(x * x + y * y) * METERS_PER_DEGREE).roundToInt()
    }

    private data class RankedPlace(
        val place: IndexedTransitPlace,
        val normalizedName: String,
        val matchRank: Int,
        val distanceMeters: Int
    )

    private val TOKEN = Regex("[\u0430-\u044fa-z0-9]+")
    private val IGNORED_WORDS = setOf(
        "м",
        "метро",
        "мцк",
        "мцд",
        "станция",
        "станции",
        "остановка",
        "платформа"
    )
    private const val METERS_PER_DEGREE = 111_320.0
}

package app.humanrouter.search

import android.content.Context
import android.location.Address
import android.location.Geocoder
import app.humanrouter.routing.GeoPoint
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Low-latency address resolver used by the route button.
 *
 * Android's device geocoder and Photon are queried in parallel. The first useful answer wins,
 * so a slow or temporarily unavailable provider cannot block route construction for many seconds.
 */
internal object FastAddressResolver {
    private const val MAX_CACHE = 96
    private const val DEFAULT_BUDGET_MS = 1_450L
    private val workers = Executors.newFixedThreadPool(4)
    private val cache = object : LinkedHashMap<String, List<SearchPlace>>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchPlace>>?): Boolean =
            size > MAX_CACHE
    }

    fun search(
        context: Context,
        query: String,
        focus: GeoPoint? = null,
        limit: Int = 6,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): List<SearchPlace> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val boundedLimit = limit.coerceIn(1, 10)
        val key = buildString {
            append(clean.lowercase(Locale.ROOT)).append('|').append(boundedLimit)
            focus?.let { append('|').append((it.lat * 1000).toInt()).append(':').append((it.lon * 1000).toInt()) }
        }
        synchronized(cache) { cache[key]?.let { return it } }

        val completion = ExecutorCompletionService<List<SearchPlace>>(workers)
        val tasks = listOf(
            completion.submit { deviceSearch(context.applicationContext, clean, boundedLimit) },
            completion.submit { runCatching { PhotonGeocoder.search(clean, focus, boundedLimit) }.getOrDefault(emptyList()) }
        )

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceIn(350L, 3_000L))
        val merged = LinkedHashMap<String, SearchPlace>()
        repeat(tasks.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return@repeat
            val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
            val result = runCatching { future.get() }.getOrDefault(emptyList())
            for (place in result) {
                val placeKey = "${(place.point.lat * 100000).toInt()}:${(place.point.lon * 100000).toInt()}:${place.title.lowercase(Locale.ROOT)}"
                merged.putIfAbsent(placeKey, place)
                if (merged.size >= boundedLimit) break
            }
            if (merged.isNotEmpty()) {
                val answer = merged.values.take(boundedLimit)
                synchronized(cache) { cache[key] = answer }
                tasks.forEach { if (!it.isDone) it.cancel(true) }
                return answer
            }
        }

        tasks.forEach { if (!it.isDone) it.cancel(true) }
        val answer = merged.values.take(boundedLimit)
        if (answer.isNotEmpty()) synchronized(cache) { cache[key] = answer }
        return answer
    }

    @Suppress("DEPRECATION")
    private fun deviceSearch(context: Context, query: String, limit: Int): List<SearchPlace> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale("ru", "RU"))
        val addresses = runCatching {
            geocoder.getFromLocationName(
                query,
                limit,
                MOSCOW_SOUTH,
                MOSCOW_WEST,
                MOSCOW_NORTH,
                MOSCOW_EAST
            )
        }.getOrNull().orEmpty()
        return addresses.mapNotNull(::toSearchPlace).distinctBy {
            "${(it.point.lat * 100000).toInt()}:${(it.point.lon * 100000).toInt()}"
        }.take(limit)
    }

    private fun toSearchPlace(address: Address): SearchPlace? {
        val lat = address.latitude
        val lon = address.longitude
        if (!lat.isFinite() || !lon.isFinite()) return null
        val streetAndHouse = listOfNotNull(
            address.thoroughfare?.takeIf(String::isNotBlank),
            address.subThoroughfare?.takeIf(String::isNotBlank)
        ).joinToString(" ")
        val title = address.featureName?.takeIf(String::isNotBlank)
            ?: streetAndHouse.takeIf(String::isNotBlank)
            ?: address.getAddressLine(0)?.substringBefore(',')?.takeIf(String::isNotBlank)
            ?: return null
        val subtitle = listOfNotNull(
            streetAndHouse.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
            address.subLocality?.takeIf(String::isNotBlank),
            address.locality?.takeIf(String::isNotBlank),
            address.adminArea?.takeIf(String::isNotBlank)
        ).distinct().take(3).joinToString(", ")
        return SearchPlace(title, subtitle, GeoPoint(lat, lon))
    }

    // Runtime routing is Moscow-focused, but keep the address resolver broad enough for the
    // surrounding region so border addresses are not silently discarded.
    private const val MOSCOW_SOUTH = 54.70
    private const val MOSCOW_WEST = 35.00
    private const val MOSCOW_NORTH = 57.15
    private const val MOSCOW_EAST = 40.50
}

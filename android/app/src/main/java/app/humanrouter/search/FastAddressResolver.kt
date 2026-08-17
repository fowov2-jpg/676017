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
 * Low-latency Moscow address resolver used by both live suggestions and A -> B routing.
 *
 * The installed Moscow address index is authoritative for ordinary street/house input and never
 * touches the network. Android Geocoder + Photon remain fallback providers for places that are not
 * yet covered by the installed address pack. Results are ranked by street/house-token agreement so
 * a generic street centroid cannot beat an exact building candidate merely because it returned first.
 */
internal object FastAddressResolver {
    private const val MAX_CACHE = 128
    private const val DEFAULT_BUDGET_MS = 1_550L
    private const val EXACT_HOUSE_SCORE = 120
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
            append(normalize(clean)).append('|').append(boundedLimit)
            focus?.let { append('|').append((it.lat * 1000).toInt()).append(':').append((it.lon * 1000).toInt()) }
        }
        synchronized(cache) { cache[key]?.let { return it } }

        // Offline address lookup is synchronous SQLite over an indexed immutable runtime pack. It
        // should win before any provider that can incur DNS/radio latency. If the pack has a match,
        // returning it immediately is what makes typed Moscow addresses deterministic in airplane mode.
        val offline = OfflineAddressIndex.search(
            context = context.applicationContext,
            query = clean,
            focus = focus,
            limit = boundedLimit
        )
        if (offline.isNotEmpty()) {
            val answer = rank(clean, offline).take(boundedLimit)
            synchronized(cache) { cache[key] = answer }
            return answer
        }

        val variants = queryVariants(clean)
        val completion = ExecutorCompletionService<List<SearchPlace>>(workers)
        val tasks = listOf(
            completion.submit { deviceSearchVariants(context.applicationContext, variants, boundedLimit) },
            completion.submit { photonSearchVariants(variants, focus, boundedLimit) }
        )

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceIn(450L, 3_000L))
        val merged = LinkedHashMap<String, SearchPlace>()
        val houseToken = extractHouseToken(clean)
        var completed = 0

        while (completed < tasks.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            completed++
            val result = runCatching { future.get() }.getOrDefault(emptyList())
            for (place in result) {
                val placeKey = "${(place.point.lat * 100000).toInt()}:${(place.point.lon * 100000).toInt()}:${place.title.lowercase(Locale.ROOT)}"
                merged.putIfAbsent(placeKey, place)
            }

            val rankedNow = rank(clean, merged.values)
            // For a house-number query, wait for the second provider if the first answer is only
            // a street/district centroid. Exact house matches may arrive a few hundred ms later.
            if (rankedNow.isNotEmpty() && (houseToken == null || score(clean, rankedNow.first()) >= EXACT_HOUSE_SCORE)) {
                val answer = rankedNow.take(boundedLimit)
                synchronized(cache) { cache[key] = answer }
                tasks.forEach { if (!it.isDone) it.cancel(true) }
                return answer
            }
        }

        tasks.forEach { if (!it.isDone) it.cancel(true) }
        val answer = rank(clean, merged.values).take(boundedLimit)
        if (answer.isNotEmpty()) synchronized(cache) { cache[key] = answer }
        return answer
    }

    private fun deviceSearchVariants(context: Context, variants: List<String>, limit: Int): List<SearchPlace> {
        for (variant in variants) {
            val result = deviceSearch(context, variant, limit)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun photonSearchVariants(variants: List<String>, focus: GeoPoint?, limit: Int): List<SearchPlace> {
        for (variant in variants) {
            val result = runCatching { PhotonGeocoder.search(variant, focus, limit) }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        return emptyList()
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

    private fun queryVariants(query: String): List<String> {
        val compact = query.replace(Regex("\\s+"), " ").trim().trim(',')
        val canonical = canonicalProviderVariant(compact)
        val lower = normalize(canonical)
        val hasMoscow = lower.contains("москва") || lower.contains("московская область")
        val hasStreetType = STREET_WORDS.any(lower::contains)
        val house = extractHouseToken(canonical)
        return buildList {
            if (!hasMoscow && house != null && !hasStreetType) {
                val streetPart = canonical.substringBeforeLast(house).trim().trim(',', '.', ' ')
                if (streetPart.isNotBlank()) {
                    add("Москва, улица $streetPart, дом $house")
                }
            }
            if (!hasMoscow) add("Москва, $canonical")
            if (canonical != compact && !hasMoscow) add("Москва, $compact")
            add(canonical)
            if (canonical != compact) add(compact)
        }.distinct()
    }

    private fun canonicalProviderVariant(query: String): String = query
        .replace(Regex("(?i)(?:^|\\s)ул\\.?\\s+")) { match -> match.value.substringBefore("ул") + "улица " }
        .replace(Regex("(?i)(?:^|\\s)пр-?т\\.?\\s+")) { match -> match.value.substringBefore("пр") + "проспект " }
        .replace(Regex("(?i)(?:^|\\s)пер\\.?\\s+")) { match -> match.value.substringBefore("пер") + "переулок " }
        .replace(Regex("(?i)(?:^|\\s)наб\\.?\\s+")) { match -> match.value.substringBefore("наб") + "набережная " }
        .replace(Regex("(?i)(?:^|\\s)пл\\.?\\s+")) { match -> match.value.substringBefore("пл") + "площадь " }
        .replace(Regex("(?i)\\bкорп\\.?\\s*"), "корпус ")
        .replace(Regex("(?i)\\bстр\\.?\\s*"), "строение ")
        .replace(Regex("(?i)\\bвл\\.?\\s*"), "владение ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun rank(query: String, places: Collection<SearchPlace>): List<SearchPlace> =
        places.sortedWith(compareByDescending<SearchPlace> { score(query, it) }.thenBy { it.title.length })

    private fun score(query: String, place: SearchPlace): Int {
        val q = normalize(query)
        val address = normalize("${place.title} ${place.subtitle}")
        var result = 0
        val house = extractHouseToken(query)
        if (house != null) {
            // A base-house query such as "13" must match real Moscow addresses "13к1" and
            // "13 корпус 2". Those are distinct suggestions, not a reason to drop the result.
            val houseRegex = Regex(
                "(^|\\D)${Regex.escape(house)}(?:[а-яa-z]\\d*|\\s*(?:к|корп|корпус|с|стр|строение)\\s*\\d+)?(\\D|$)",
                RegexOption.IGNORE_CASE
            )
            if (houseRegex.containsMatchIn(address)) result += 90 else result -= 35
        }
        val words = q.split(' ')
            .filter { it.length >= 3 && it !in STOP_WORDS && it.none(Char::isDigit) }
            .distinct()
        for (word in words) {
            if (address.contains(word)) result += 32
        }
        if (address.contains(q)) result += 80
        if (address.contains("москва")) result += 8
        return result
    }

    private fun extractHouseToken(query: String): String? {
        val normalized = normalize(query)
        val explicit = Regex("(?:^|\\s)(?:д|дом|вл|владение)\\s*(\\d+[а-яa-z]?)", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(String::isNotBlank)
        if (explicit != null) return explicit

        // A suffix such as "10 корпус 2" belongs to house 10. Do not mistake the corpus/building
        // number for the base house number merely because it is the last numeric token.
        val trailingAddress = Regex(
            "(?:^|\\s)(\\d+[а-яa-z]?)(?:\\s*(?:к|корп|корпус)\\s*\\d+[а-яa-z]?)?(?:\\s*(?:с|стр|строение)\\s*\\d+[а-яa-z]?)?\\s*$",
            RegexOption.IGNORE_CASE
        ).find(normalized)
        trailingAddress?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)?.let { return it }

        // Fall back to the last numeric token for simple input such as "1-я Тверская-Ямская 13".
        return Regex("(?:^|\\s)(\\d+[а-яa-z]?)", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) }
            .lastOrNull()
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale("ru", "RU"))
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9]+"), " ")
        .trim()

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
        ).distinct().take(4).joinToString(", ")
        return SearchPlace(title, subtitle, GeoPoint(lat, lon))
    }

    internal fun queryVariantsForTest(query: String): List<String> = queryVariants(query)

    internal fun rankForTest(query: String, places: Collection<SearchPlace>): List<SearchPlace> = rank(query, places)

    private val STREET_WORDS = listOf(
        "улица", "ул ", "проспект", "пр т", "переулок", "шоссе", "бульвар", "набережная",
        "проезд", "площадь", "аллея", "линия", "тупик", "квартал", "микрорайон", "мкр"
    )
    private val STOP_WORDS = setOf(
        "москва", "улица", "дом", "владение", "корпус", "корп", "строение", "стр"
    )

    // Routing runtime is Moscow-focused, while fallback geocoding intentionally includes the nearby
    // Moscow region so border addresses are not silently rejected.
    private const val MOSCOW_SOUTH = 54.70
    private const val MOSCOW_WEST = 35.00
    private const val MOSCOW_NORTH = 57.15
    private const val MOSCOW_EAST = 40.50
}

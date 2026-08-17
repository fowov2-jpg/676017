package app.humanrouter.search

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.humanrouter.routing.GeoPoint
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Read-only address lookup over the installed Moscow runtime pack.
 *
 * Expected path: runtime/address/address.sqlite
 * Schema is intentionally small and immutable so RuntimeInstaller can checksum/atomically replace
 * the database just like the routing packs. Network is never touched by this class.
 */
internal object OfflineAddressIndex {
    private const val SCHEMA_VERSION = 1

    data class ParsedQuery(
        val street: String,
        val house: String?
    )

    fun isAvailable(context: Context): Boolean =
        File(context.filesDir, DATABASE_PATH).isFile

    fun search(
        context: Context,
        query: String,
        focus: GeoPoint? = null,
        limit: Int = 6
    ): List<SearchPlace> {
        val databaseFile = File(context.filesDir, DATABASE_PATH)
        if (!databaseFile.isFile) return emptyList()
        val parsed = parse(query) ?: return emptyList()
        val bounded = limit.coerceIn(1, 10)

        return runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { database ->
                verifySchema(database)
                query(database, parsed, focus, bounded)
            }
        }.getOrDefault(emptyList())
    }

    private fun verifySchema(database: SQLiteDatabase) {
        database.rawQuery(
            "SELECT schema_version FROM metadata LIMIT 1",
            null
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Offline address metadata is empty" }
            require(cursor.getInt(0) == SCHEMA_VERSION) {
                "Unsupported offline address schema=${cursor.getInt(0)}"
            }
        }
    }

    private fun query(
        database: SQLiteDatabase,
        parsed: ParsedQuery,
        focus: GeoPoint?,
        limit: Int
    ): List<SearchPlace> {
        val streetKey = compact(parsed.street)
        if (streetKey.length < 2) return emptyList()
        val houseKey = parsed.house?.let(::compact)?.takeIf(String::isNotBlank)
        val rows = ArrayList<AddressRow>(limit * 4)

        val sql: String
        val args: Array<String>
        if (houseKey != null) {
            sql = """
                SELECT street,house,district,locality,postcode,lat,lon,norm_street,norm_house
                  FROM addresses
                 WHERE norm_street LIKE ?
                   AND (norm_house=? OR norm_house LIKE ?)
                 ORDER BY
                       CASE WHEN norm_street=? THEN 0 ELSE 1 END,
                       CASE WHEN norm_house=? THEN 0 ELSE 1 END,
                       length(norm_street),length(norm_house)
                 LIMIT ?
            """.trimIndent()
            args = arrayOf(
                "$streetKey%",
                houseKey,
                "$houseKey%",
                streetKey,
                houseKey,
                (limit * 8).coerceAtMost(80).toString()
            )
        } else {
            sql = """
                SELECT street,house,district,locality,postcode,lat,lon,norm_street,norm_house
                  FROM addresses
                 WHERE norm_street LIKE ?
                 ORDER BY CASE WHEN norm_street=? THEN 0 ELSE 1 END,length(norm_street),norm_house
                 LIMIT ?
            """.trimIndent()
            args = arrayOf(
                "$streetKey%",
                streetKey,
                (limit * 8).coerceAtMost(80).toString()
            )
        }

        database.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toAddressRow()
        }

        return rows.asSequence()
            .filter { it.lat.isFinite() && it.lon.isFinite() }
            .sortedWith(
                compareByDescending<AddressRow> { score(parsed, it) }
                    .thenBy { row -> focus?.let { haversineMeters(it, GeoPoint(row.lat, row.lon)) } ?: 0.0 }
                    .thenBy { it.street.length }
                    .thenBy { it.house.length }
            )
            .distinctBy { "${it.normStreet}|${it.normHouse}|${(it.lat * 100000).toInt()}:${(it.lon * 100000).toInt()}" }
            .take(limit)
            .map(::toSearchPlace)
            .toList()
    }

    private fun score(parsed: ParsedQuery, row: AddressRow): Int {
        val street = compact(parsed.street)
        val house = parsed.house?.let(::compact)
        var score = 0
        if (row.normStreet == street) score += 160
        else if (row.normStreet.startsWith(street)) score += 110
        else if (row.normStreet.contains(street)) score += 60

        if (house != null) {
            when {
                row.normHouse == house -> score += 180
                row.normHouse.startsWith(house) -> score += 120
                else -> score -= 120
            }
        } else if (row.normHouse.isNotBlank()) {
            score += 10
        }
        return score
    }

    private fun toSearchPlace(row: AddressRow): SearchPlace {
        val title = buildString {
            append(row.street)
            if (row.house.isNotBlank()) append(", ").append(row.house)
        }
        val subtitle = listOf(row.district, row.locality, row.postcode)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")
        return SearchPlace(title, subtitle, GeoPoint(row.lat, row.lon))
    }

    /** Parses normal Moscow input without requiring a street-type prefix. */
    internal fun parse(query: String): ParsedQuery? {
        var normalized = normalizeWords(query)
        if (normalized.length < 2) return null
        normalized = normalized
            .replace(Regex("^(?:г(?:ород)?\\s+)?москва\\s+"), "")
            .replace(Regex("^московская\\s+область\\s+"), "")
            .trim()

        val explicitHouse = Regex(
            "(?:^|\\s)(?:д|дом)\\s*(\\d+[а-яa-z]?)(?:\\s*(?:к|корпус)\\s*(\\d+[а-яa-z]?))?(?:\\s*(?:стр|строение)\\s*(\\d+[а-яa-z]?))?\\s*$"
        ).find(normalized)

        val houseRange = explicitHouse?.range
        val house = if (explicitHouse != null) {
            buildString {
                append(explicitHouse.groupValues[1])
                explicitHouse.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)?.let { append("к").append(it) }
                explicitHouse.groupValues.getOrNull(3)?.takeIf(String::isNotBlank)?.let { append("с").append(it) }
            }
        } else {
            val trailing = Regex(
                "(?:^|\\s)(\\d+[а-яa-z]?)(?:\\s*(?:к|корпус)\\s*(\\d+[а-яa-z]?))?(?:\\s*(?:стр|строение)\\s*(\\d+[а-яa-z]?))?\\s*$"
            ).find(normalized)
            if (trailing != null && hasStreetBeforeNumber(normalized, trailing.range.first)) {
                buildString {
                    append(trailing.groupValues[1])
                    trailing.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)?.let { append("к").append(it) }
                    trailing.groupValues.getOrNull(3)?.takeIf(String::isNotBlank)?.let { append("с").append(it) }
                }
            } else null
        }

        val trailingMatch = if (houseRange == null && house != null) {
            Regex(
                "(?:^|\\s)(\\d+[а-яa-z]?)(?:\\s*(?:к|корпус)\\s*(\\d+[а-яa-z]?))?(?:\\s*(?:стр|строение)\\s*(\\d+[а-яa-z]?))?\\s*$"
            ).find(normalized)
        } else null
        val removeFrom = houseRange?.first ?: trailingMatch?.range?.first
        var street = if (removeFrom != null) normalized.substring(0, removeFrom) else normalized
        street = stripStreetType(street.trim())
        if (street.length < 2) return null
        return ParsedQuery(street = street, house = house)
    }

    private fun hasStreetBeforeNumber(value: String, numberStart: Int): Boolean =
        value.substring(0, numberStart.coerceAtLeast(0)).any(Char::isLetter)

    private fun stripStreetType(value: String): String = value
        .replace(
            Regex(
                "^(?:ул(?:ица)?|проспект|пр-т|пр-т|переулок|пер|шоссе|бульвар|бул|набережная|наб|проезд|площадь|пл)\\s+"
            ),
            ""
        )
        .replace(
            Regex(
                "\\s+(?:ул(?:ица)?|проспект|пр-т|переулок|пер|шоссе|бульвар|бул|набережная|наб|проезд|площадь|пл)$"
            ),
            ""
        )
        .trim()

    /** Stable producer/reader key: ё=e, punctuation and spaces do not affect lookup. */
    internal fun compact(value: String): String = normalizeWords(value).replace(" ", "")

    internal fun normalizeWords(value: String): String = value
        .lowercase(Locale("ru", "RU"))
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun Cursor.toAddressRow(): AddressRow = AddressRow(
        street = getString(0).orEmpty(),
        house = getString(1).orEmpty(),
        district = getString(2).orEmpty(),
        locality = getString(3).orEmpty(),
        postcode = getString(4).orEmpty(),
        lat = getDouble(5),
        lon = getDouble(6),
        normStreet = getString(7).orEmpty(),
        normHouse = getString(8).orEmpty()
    )

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = a.lat * PI / 180.0
        val p2 = b.lat * PI / 180.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val q = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(q)))
    }

    private data class AddressRow(
        val street: String,
        val house: String,
        val district: String,
        val locality: String,
        val postcode: String,
        val lat: Double,
        val lon: Double,
        val normStreet: String,
        val normHouse: String
    )

    const val DATABASE_PATH = "runtime/address/address.sqlite"
    private const val EARTH_RADIUS_METERS = 6_371_000.0
}

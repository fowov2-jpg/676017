package app.humanrouter.search

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.humanrouter.VremyaHodomApp
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineAddressIndexInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<VremyaHodomApp>()
    private val addressRoot = File(context.filesDir, "runtime/address")
    private val databaseFile = File(addressRoot, "address.sqlite")

    @Before
    fun installSyntheticOfflineAddresses() {
        addressRoot.deleteRecursively()
        addressRoot.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("CREATE TABLE metadata (schema_version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO metadata(schema_version) VALUES (1)")
            db.execSQL(
                """
                CREATE TABLE addresses (
                    id INTEGER PRIMARY KEY,
                    street TEXT NOT NULL,
                    house TEXT NOT NULL,
                    district TEXT NOT NULL DEFAULT '',
                    locality TEXT NOT NULL DEFAULT '',
                    postcode TEXT NOT NULL DEFAULT '',
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    norm_street TEXT NOT NULL,
                    norm_house TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX address_street_house ON addresses(norm_street,norm_house)")
            insert(db, 1, "улица Шумилова", "13", "Текстильщики", 55.70744, 37.73654)
            insert(db, 2, "Тверская улица", "7", "Тверской", 55.75920, 37.61110)
            insert(db, 3, "Тверская улица", "7к1", "Тверской", 55.75935, 37.61125)
            insert(db, 4, "Ленинградский проспект", "36с11", "Аэропорт", 55.79295, 37.54320)
        }
    }

    @After
    fun cleanup() {
        addressRoot.deleteRecursively()
    }

    @Test
    fun normalStreetAndHouseResolveEntirelyFromInstalledRuntime() {
        val started = SystemClock.elapsedRealtime()
        val places = FastAddressResolver.search(
            context = context,
            query = "Шумилова 13",
            limit = 5,
            budgetMs = 450L
        )
        val elapsed = SystemClock.elapsedRealtime() - started

        assertTrue("offline address lookup exceeded 450 ms: ${elapsed}ms", elapsed <= 450L)
        assertTrue("Шумилова 13 not found in offline runtime: $places", places.isNotEmpty())
        assertTrue(places.first().title.contains("Шумилова", ignoreCase = true))
        assertTrue(places.first().title.contains("13"))
    }

    @Test
    fun houseCorpusAndBuildingVariantsAreParsedWithoutNetwork() {
        val corpus = FastAddressResolver.search(
            context = context,
            query = "Тверская 7 корпус 1",
            limit = 5,
            budgetMs = 450L
        )
        assertTrue("building corpus missing: $corpus", corpus.isNotEmpty())
        assertEquals("Тверская улица, 7к1", corpus.first().title)

        val building = FastAddressResolver.search(
            context = context,
            query = "Ленинградский проспект 36 строение 11",
            limit = 5,
            budgetMs = 450L
        )
        assertTrue("building/structure address missing: $building", building.isNotEmpty())
        assertEquals("Ленинградский проспект, 36с11", building.first().title)
    }

    private fun insert(
        database: SQLiteDatabase,
        id: Int,
        street: String,
        house: String,
        district: String,
        lat: Double,
        lon: Double
    ) {
        database.insertOrThrow(
            "addresses",
            null,
            ContentValues().apply {
                put("id", id)
                put("street", street)
                put("house", house)
                put("district", district)
                put("locality", "Москва")
                put("postcode", "")
                put("lat", lat)
                put("lon", lon)
                put("norm_street", OfflineAddressIndex.compact(street))
                put("norm_house", OfflineAddressIndex.compact(house))
            }
        )
    }
}

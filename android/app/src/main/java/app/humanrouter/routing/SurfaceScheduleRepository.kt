package app.humanrouter.routing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.Closeable
import java.io.File

internal data class SurfaceStop(
    val id: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val transportType: String?
)

internal data class SurfaceRoute(
    val id: String,
    val shortName: String?,
    val longName: String?,
    val mode: TransportMode
)

internal data class SurfaceConnection(
    val departureSec: Int,
    val fromStopId: Int,
    val tripId: String,
    val sequence: Int,
    val toStopId: Int,
    val arrivalSec: Int,
    val routeId: String
)

internal class SurfaceScheduleRepository(
    context: Context
) : Closeable {
    private val runtimeRoot = File(context.filesDir, "runtime")
    private val surfaceRoot = File(runtimeRoot, "surface")
    private val manifest = JSONObject(File(surfaceRoot, "manifest.json").readText())
    private val databaseFile = File(surfaceRoot, manifest.getString("primary_file"))
    private val database = SQLiteDatabase.openDatabase(
        databaseFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY
    )

    val serviceDate: String = manifest.optString("service_date", "")

    fun loadStops(): List<SurfaceStop> {
        val result = ArrayList<SurfaceStop>(18_000)
        database.rawQuery(
            "SELECT stop_id,name,lat,lon,transport_type FROM stops",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SurfaceStop(
                    id = cursor.getInt(0),
                    name = cursor.getString(1),
                    lat = cursor.getDouble(2),
                    lon = cursor.getDouble(3),
                    transportType = if (cursor.isNull(4)) null else cursor.getString(4)
                )
            }
        }
        return result
    }

    fun loadRoutes(): Map<String, SurfaceRoute> {
        val result = HashMap<String, SurfaceRoute>(1_024)
        database.rawQuery(
            "SELECT route_id,short_name,long_name,route_mode FROM routes",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val mode = when (cursor.getString(3)?.uppercase()) {
                    "TRAM" -> TransportMode.TRAM
                    else -> TransportMode.BUS
                }
                result[id] = SurfaceRoute(
                    id = id,
                    shortName = if (cursor.isNull(1)) null else cursor.getString(1),
                    longName = if (cursor.isNull(2)) null else cursor.getString(2),
                    mode = mode
                )
            }
        }
        return result
    }

    /**
     * Streams timetable connections in departure-time order. Returning false from [visitor]
     * stops the scan early, which lets the router stop as soon as no later connection can beat
     * the best known arrival.
     */
    fun scanConnections(
        fromDepartureSec: Int,
        toDepartureSec: Int,
        visitor: (SurfaceConnection) -> Boolean
    ) {
        database.rawQuery(
            """
            SELECT dep,from_stop,trip_id,seq,to_stop,arr,route_id
            FROM connections
            WHERE dep>=? AND dep<=?
            ORDER BY dep,from_stop,trip_id,seq
            """.trimIndent(),
            arrayOf(fromDepartureSec.toString(), toDepartureSec.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val connection = SurfaceConnection(
                    departureSec = cursor.getInt(0),
                    fromStopId = cursor.getInt(1),
                    tripId = cursor.getString(2),
                    sequence = cursor.getInt(3),
                    toStopId = cursor.getInt(4),
                    arrivalSec = cursor.getInt(5),
                    routeId = cursor.getString(6)
                )
                if (!visitor(connection)) break
            }
        }
    }

    override fun close() {
        database.close()
    }
}

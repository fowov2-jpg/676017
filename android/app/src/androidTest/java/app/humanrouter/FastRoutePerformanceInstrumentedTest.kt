package app.humanrouter

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.humanrouter.routing.FastMeetRouter
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.HumanRouterEngine
import app.humanrouter.routing.RoutePreferences
import app.humanrouter.routing.TransportMode
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class FastRoutePerformanceInstrumentedTest {
    @Test
    fun publishedMoscowRailPreviewStaysUnderTwoSecondsAfterPrewarm() {
        val context = ApplicationProvider.getApplicationContext<VremyaHodomApp>()
        val graphFile = File(context.filesDir, "runtime/rail/graph.json")
        graphFile.parentFile?.mkdirs()
        downloadPublishedRailGraph(graphFile)

        try {
            val samples = representativeRoutes(graphFile)
            assertTrue("published rail graph has too few representative route samples", samples.size >= 3)

            val router = FastMeetRouter.get(context, RoutePreferences())
            val departure = Instant.now().epochSecond

            // Preload is intentionally outside the user-visible route SLA. Production starts this
            // work as soon as the active lifecycle is installed, while the user is choosing A/B.
            // Some Android runtimes parse the cold JSON graph substantially slower than others;
            // do not mislabel that one-time background initialization as route-search latency.
            router.prewarm()
            val preload = awaitTransitPrewarm(router, samples.first(), departure)

            val report = buildString {
                appendLine("runtime=runtime-current/rail_graph.json.gz")
                appendLine("target_ms=${FastRoutePlanner.FIRST_RESULT_TARGET_MS}")
                appendLine("prewarm_elapsed_ms=${preload.elapsedMs} attempts=${preload.attempts}")
                samples.take(3).forEachIndexed { index, sample ->
                    val started = SystemClock.elapsedRealtime()
                    val result = router.planPreview(
                        origin = sample.origin,
                        destination = sample.destination,
                        departureEpochSec = departure + index * 60L,
                        budgetMs = FastRoutePlanner.PREVIEW_BUDGET_MS
                    )
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val success = result as? HumanRouterEngine.PlanResult.Success
                        ?: throw AssertionError("fast preview failed for ${sample.name}: $result")
                    val hasTransit = success.fastest.route.legs.any { it.mode != TransportMode.WALK }
                    appendLine(
                        "sample=${sample.name} distance_m=${sample.distanceMeters.toInt()} " +
                            "elapsed_ms=$elapsed transit=$hasTransit result=${result.javaClass.simpleName}"
                    )
                    assertTrue("preview for ${sample.name} contains no transport leg", hasTransit)
                    assertTrue(
                        "first route for ${sample.name} took ${elapsed}ms, target=${FastRoutePlanner.FIRST_RESULT_TARGET_MS}ms",
                        elapsed <= FastRoutePlanner.FIRST_RESULT_TARGET_MS
                    )
                }
            }

            val output = File(requireNotNull(context.getExternalFilesDir(null)), "fast-route-performance.txt")
            output.writeText(report)
            android.util.Log.i("VremyaHodomRoutePerf", report.replace('\n', ' '))
        } finally {
            // The benchmark installs a rail runtime solely for this test. Do not leave its graph,
            // executor threads or parsed indexes resident for the remaining instrumentation tests.
            FastMeetRouter.clearCachedForTest()
            graphFile.delete()
            System.gc()
        }
    }

    private fun awaitTransitPrewarm(
        router: FastMeetRouter,
        sample: RouteSample,
        departureEpochSec: Long
    ): PrewarmResult {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + PREWARM_TIMEOUT_MS
        var attempts = 0
        var lastResult: HumanRouterEngine.PlanResult? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts++
            lastResult = router.planPreview(
                origin = sample.origin,
                destination = sample.destination,
                departureEpochSec = departureEpochSec,
                budgetMs = 1_300L
            )
            val success = lastResult as? HumanRouterEngine.PlanResult.Success
            if (success != null && success.fastest.route.legs.any { it.mode != TransportMode.WALK }) {
                return PrewarmResult(
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    attempts = attempts
                )
            }
            SystemClock.sleep(PREWARM_RETRY_DELAY_MS)
        }
        throw AssertionError(
            "fast router did not become transit-ready within ${PREWARM_TIMEOUT_MS}ms; " +
                "attempts=$attempts last=$lastResult"
        )
    }

    private fun downloadPublishedRailGraph(target: File) {
        val connection = URL(RAIL_GRAPH_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "VremyaHodom-AndroidTest/1.0")
        try {
            assertTrue("rail graph HTTP status=${connection.responseCode}", connection.responseCode in 200..299)
            GZIPInputStream(connection.inputStream).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        assertTrue("published rail graph is empty", target.length() > 1_000L)
    }

    private fun representativeRoutes(graphFile: File): List<RouteSample> {
        val root = JSONObject(graphFile.readText())
        val routes = root.getJSONArray("routes")
        val candidates = ArrayList<Pair<Int, RouteSample>>()
        for (index in 0 until routes.length()) {
            val route = routes.getJSONObject(index)
            if (!route.optBoolean("routeable", false)) continue
            val mode = route.optString("mode")
            if (mode != "METRO" && mode != "MCC") continue
            val stops = route.optJSONArray("stops") ?: continue
            if (stops.length() < 8) continue

            val pairs = listOf(
                0 to (stops.length() / 2),
                (stops.length() / 4) to ((stops.length() * 3) / 4),
                1 to (stops.length() - 2)
            )
            for ((fromIndex, toIndex) in pairs) {
                if (fromIndex !in 0 until stops.length() || toIndex !in 0 until stops.length() || fromIndex >= toIndex) continue
                val from = stops.getJSONObject(fromIndex)
                val to = stops.getJSONObject(toIndex)
                val origin = GeoPoint(from.getDouble("lat"), from.getDouble("lon"))
                val destination = GeoPoint(to.getDouble("lat"), to.getDouble("lon"))
                val distance = haversineMeters(origin, destination)
                // This is deliberately above FastMeetRouter's direct-walk preview limit. A PASS
                // therefore proves that rail routing, not a trivial walking shortcut, was measured.
                if (distance < MIN_TRANSIT_SAMPLE_METERS) continue
                val relationId = route.optString("osm_relation_id", index.toString())
                val sample = RouteSample(
                    name = "$mode:$relationId:${from.optString("name")}-${to.optString("name")}",
                    origin = origin,
                    destination = destination,
                    distanceMeters = distance
                )
                candidates += stops.length() to sample
            }
        }
        return candidates
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.name }
    }

    private fun haversineMeters(from: GeoPoint, to: GeoPoint): Double {
        val p1 = from.lat * PI / 180.0
        val p2 = to.lat * PI / 180.0
        val dLat = (to.lat - from.lat) * PI / 180.0
        val dLon = (to.lon - from.lon) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    private data class RouteSample(
        val name: String,
        val origin: GeoPoint,
        val destination: GeoPoint,
        val distanceMeters: Double
    )

    private data class PrewarmResult(
        val elapsedMs: Long,
        val attempts: Int
    )

    companion object {
        private const val RAIL_GRAPH_URL =
            "https://github.com/fowov2-jpg/676017/releases/download/runtime-current/rail_graph.json.gz"
        private const val MIN_TRANSIT_SAMPLE_METERS = 3_000.0
        private const val PREWARM_TIMEOUT_MS = 10_000L
        private const val PREWARM_RETRY_DELAY_MS = 80L
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

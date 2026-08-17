package app.humanrouter

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.humanrouter.routing.FastMeetRouter
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.HumanRouterEngine
import app.humanrouter.routing.RoutePreferences
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class FastRoutePerformanceInstrumentedTest {
    @Test
    fun publishedMoscowRailPreviewStaysUnderTwoSecondsAfterPrewarm() {
        val context = ApplicationProvider.getApplicationContext<VremyaHodomApp>()
        val graphFile = File(context.filesDir, "runtime/rail/graph.json")
        graphFile.parentFile?.mkdirs()
        downloadPublishedRailGraph(graphFile)

        val samples = representativeRoutes(graphFile)
        assertTrue("published rail graph has too few representative route samples", samples.size >= 3)

        val router = FastMeetRouter.get(context, RoutePreferences())
        val departure = Instant.now().epochSecond

        // The product contract is for an installed/prewarmed runtime. Make the preload deterministic
        // rather than accidentally timing one-time JSON parsing as user-visible route latency.
        val warmup = router.planPreview(
            origin = samples.first().origin,
            destination = samples.first().destination,
            departureEpochSec = departure,
            budgetMs = 1_300L
        )
        assertTrue("fast router warmup failed on published Moscow rail graph", warmup is HumanRouterEngine.PlanResult.Success)

        val report = buildString {
            appendLine("runtime=runtime-current/rail_graph.json.gz")
            appendLine("target_ms=${FastRoutePlanner.FIRST_RESULT_TARGET_MS}")
            samples.take(3).forEachIndexed { index, sample ->
                val started = SystemClock.elapsedRealtime()
                val result = router.planPreview(
                    origin = sample.origin,
                    destination = sample.destination,
                    departureEpochSec = departure + index * 60L,
                    budgetMs = FastRoutePlanner.PREVIEW_BUDGET_MS
                )
                val elapsed = SystemClock.elapsedRealtime() - started
                appendLine("sample=${sample.name} elapsed_ms=$elapsed result=${result.javaClass.simpleName}")
                assertTrue("fast preview failed for ${sample.name}: $result", result is HumanRouterEngine.PlanResult.Success)
                assertTrue(
                    "first route for ${sample.name} took ${elapsed}ms, target=${FastRoutePlanner.FIRST_RESULT_TARGET_MS}ms",
                    elapsed <= FastRoutePlanner.FIRST_RESULT_TARGET_MS
                )
            }
        }

        val output = File(requireNotNull(context.getExternalFilesDir(null)), "fast-route-performance.txt")
        output.writeText(report)
        android.util.Log.i("VremyaHodomRoutePerf", report.replace('\n', ' '))
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
            if (stops.length() < 5) continue
            val from = stops.getJSONObject(0)
            val to = stops.getJSONObject(stops.length() - 1)
            val sample = RouteSample(
                name = "${mode}:${route.optString("ref", route.optString("name", index.toString()))}:${from.optString("name")}-${to.optString("name")}",
                origin = GeoPoint(from.getDouble("lat"), from.getDouble("lon")),
                destination = GeoPoint(to.getDouble("lat"), to.getDouble("lon"))
            )
            candidates += stops.length() to sample
        }
        return candidates
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.name }
    }

    private data class RouteSample(
        val name: String,
        val origin: GeoPoint,
        val destination: GeoPoint
    )

    companion object {
        private const val RAIL_GRAPH_URL =
            "https://github.com/fowov2-jpg/676017/releases/download/runtime-current/rail_graph.json.gz"
    }
}

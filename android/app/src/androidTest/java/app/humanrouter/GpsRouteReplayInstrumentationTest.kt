package app.humanrouter

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class GpsRouteReplayInstrumentationTest {

    private data class ReplayStep(
        val name: String,
        val legIndex: Int,
        val fraction: Double,
        val epochOffsetSec: Long,
        val expectedPhase: TripProgressPhase,
        val expectedCopy: String
    )

    @Test
    fun gpsReplayAdvancesThroughBusTransferMetroAndFinish() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "gps-replay"
        ).apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            launchTrip().use { scenario ->
                instrumentation.waitForIdleSync()
                SystemClock.sleep(180L)
                instrumentation.waitForIdleSync()

                val route = checkNotNull(LastPlanStore.seed?.route) { "QA trip route was not installed" }
                assertEquals("qa-bus-metro", route.id)
                assertTrue("GPS replay needs a multimodal transfer route", route.legs.size >= 6)

                TripProgressState.clear()
                val replay = listOf(
                    ReplayStep("gps-01-approach", 0, 0.45, midpoint(route.legs[0]), TripProgressPhase.APPROACH, "Идём к остановке"),
                    ReplayStep("gps-02-stop", 1, 0.55, midpoint(route.legs[1]), TripProgressPhase.APPROACH, "Идём к остановке"),
                    ReplayStep("gps-03-bus-wait", 2, 0.02, route.legs[2].departureEpochSec - 8L, TripProgressPhase.WAITING, "Ждём автобус"),
                    ReplayStep("gps-04-bus-onboard", 2, 0.46, midpoint(route.legs[2]), TripProgressPhase.ONBOARD, "В пути"),
                    ReplayStep("gps-05-bus-exit", 2, 0.89, route.legs[2].arrivalEpochSec - 55L, TripProgressPhase.ALIGHTING, "Скоро выход"),
                    ReplayStep("gps-06-transfer", 3, 0.50, midpoint(route.legs[3]), TripProgressPhase.TRANSFER, "Пересадка"),
                    ReplayStep("gps-07-metro-wait", 4, 0.02, route.legs[4].departureEpochSec - 8L, TripProgressPhase.WAITING, "Ждём метро"),
                    ReplayStep("gps-08-metro-onboard", 4, 0.48, midpoint(route.legs[4]), TripProgressPhase.ONBOARD, "В пути"),
                    ReplayStep("gps-09-metro-exit", 4, 0.90, route.legs[4].arrivalEpochSec - 55L, TripProgressPhase.ALIGHTING, "Скоро выход"),
                    ReplayStep("gps-10-final-walk", 5, 0.52, midpoint(route.legs[5]), TripProgressPhase.FINAL_WALK, "Идём к месту"),
                    ReplayStep("gps-11-finish", 5, 1.0, route.legs[5].arrivalEpochSec, TripProgressPhase.FINISHED, "Вы прибыли")
                )

                var previousLeg = -1
                replay.forEach { step ->
                    val leg = route.legs[step.legIndex]
                    val point = pointAt(leg, step.fraction)
                    val snapshot = TripProgressState.publishLocation(
                        route = route,
                        point = point,
                        epochSec = step.epochOffsetSec,
                        accuracyMeters = 6f
                    )

                    assertEquals("wrong GPS phase at ${step.name}", step.expectedPhase, snapshot.phase)
                    assertEquals("GPS attached to wrong leg at ${step.name}", step.legIndex, snapshot.legIndex)
                    assertTrue("GPS progress moved backwards at ${step.name}", snapshot.legIndex >= previousLeg)
                    previousLeg = snapshot.legIndex

                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(130L)
                    instrumentation.waitForIdleSync()

                    scenario.onActivity { activity ->
                        val root = activity.findViewById<FrameLayout>(R.id.root)
                        assertEquals("duplicate active top card at ${step.name}", 1, countTag(root, "reference_active_trip_top"))
                        assertEquals("duplicate legacy mini card at ${step.name}", 1, countTag(root, "reference_active_trip_mini"))

                        val top = checkNotNull(root.findViewWithTag<View>("reference_active_trip_top")) {
                            "active top card missing at ${step.name}"
                        }
                        val mini = checkNotNull(root.findViewWithTag<View>("reference_active_trip_mini")) {
                            "legacy active mini card missing at ${step.name}"
                        }
                        assertTrue("active top card hidden at ${step.name}", top.visibility == View.VISIBLE)
                        assertEquals(
                            "third active-trip bottom bar must stay hidden at ${step.name}",
                            View.GONE,
                            mini.visibility
                        )
                        assertEquals(
                            "global bottom navigation must stay hidden during active trip at ${step.name}",
                            View.GONE,
                            activity.findViewById<View>(R.id.bottomNav).visibility
                        )
                        assertTrue(
                            "GPS phase copy is missing at ${step.name}: ${top.contentDescription}",
                            top.contentDescription?.toString()?.contains(step.expectedCopy, ignoreCase = true) == true
                        )

                        val gps = checkNotNull(findByTag(root, "vh_unified_gps_status") as? TextView) {
                            "GPS status card missing at ${step.name}"
                        }
                        assertTrue("GPS card does not describe current stage at ${step.name}", gps.text.contains("этап ${step.legIndex + 1}"))

                        val detail = checkNotNull(findByTag(root, "vh_gps_current_stage_card") as? ViewGroup) {
                            "detailed GPS stage card missing at ${step.name}"
                        }
                        assertEquals(
                            "detailed sheet is not GPS-owned at ${step.name}",
                            "Текущий этап по GPS",
                            (detail.getChildAt(0) as? TextView)?.text?.toString()
                        )
                        val detailTitle = (detail.getChildAt(1) as? TextView)?.text?.toString().orEmpty()
                        if (step.expectedPhase == TripProgressPhase.TRANSFER) {
                            assertTrue("transfer detail does not point to metro: $detailTitle", detailTitle.contains("Метро", ignoreCase = true))
                        }
                        if (step.legIndex == 4) {
                            assertTrue("metro GPS stage is not shown as metro: $detailTitle", detailTitle.contains("Метро", ignoreCase = true))
                        }
                    }

                    capture(output, "${step.name}.png")
                }

                assertRequiredEvidence(output)
            }
        } finally {
            // This instrumentation command is followed by independent navigation/search suites in
            // the responsive matrix. The QA trip deliberately seeds both global stores; leaving the
            // selected route behind makes the next fresh Activity look as if it already has route
            // options and invalidates that test's real "no route yet" precondition.
            TripProgressState.clear()
            LastPlanStore.seed = null
        }
    }

    private fun launchTrip(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("qa_screen", "trip")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )

    private fun midpoint(leg: RouteLeg): Long =
        leg.departureEpochSec + (leg.arrivalEpochSec - leg.departureEpochSec) / 2L

    /** Interpolates by travelled polyline distance, not merely endpoint latitude/longitude. */
    private fun pointAt(leg: RouteLeg, requestedFraction: Double): GeoPoint {
        val points = leg.mapPoints()
        if (points.isEmpty()) return leg.from.point
        if (points.size == 1) return points.first()
        val fraction = requestedFraction.coerceIn(0.0, 1.0)
        if (fraction <= 0.0) return points.first()
        if (fraction >= 1.0) return points.last()

        val lengths = DoubleArray(points.lastIndex) { index -> distanceMeters(points[index], points[index + 1]) }
        val total = lengths.sum()
        if (total < 0.5) return points.first()
        val target = total * fraction
        var prefix = 0.0
        for (index in 0 until points.lastIndex) {
            val length = lengths[index]
            if (prefix + length >= target) {
                val local = if (length < 0.01) 0.0 else (target - prefix) / length
                val a = points[index]
                val b = points[index + 1]
                return GeoPoint(
                    lat = a.lat + (b.lat - a.lat) * local,
                    lon = a.lon + (b.lon - a.lon) * local
                )
            }
            prefix += length
        }
        return points.last()
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val radius = 6_371_000.0
        val refLat = ((a.lat + b.lat) / 2.0) * PI / 180.0
        val dx = (b.lon - a.lon) * PI / 180.0 * cos(refLat) * radius
        val dy = (b.lat - a.lat) * PI / 180.0 * radius
        return sqrt(dx * dx + dy * dy)
    }

    private fun countTag(root: View, tag: String): Int {
        var count = 0
        fun walk(view: View) {
            if (view.tag?.toString() == tag) count++
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return count
    }

    private fun findByTag(root: View, tag: String): View? {
        if (root.tag?.toString() == tag) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findByTag(root.getChildAt(index), tag)?.let { return it }
            }
        }
        return null
    }

    private fun capture(output: File, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        FileOutputStream(File(output, name)).use { stream ->
            assertTrue("failed to encode screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
    }

    private fun assertRequiredEvidence(output: File) {
        listOf(
            "gps-03-bus-wait.png",
            "gps-04-bus-onboard.png",
            "gps-05-bus-exit.png",
            "gps-06-transfer.png",
            "gps-07-metro-wait.png",
            "gps-08-metro-onboard.png",
            "gps-09-metro-exit.png",
            "gps-11-finish.png"
        ).forEach { name ->
            assertTrue("missing GPS replay screenshot $name", File(output, name).length() > 1_000L)
        }
    }
}

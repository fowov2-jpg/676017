package app.humanrouter.routing

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class HumanRouterEngine(
    private val context: Context,
    private val preferences: RoutePreferences = RoutePreferences(),
    private val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
) {
    sealed interface PlanResult {
        data class Success(
            val fastest: RankedRoute,
            val serviceDate: LocalDate
        ) : PlanResult

        data class RuntimeMissing(val reason: String) : PlanResult
        data class ScheduleUnavailable(val serviceDate: LocalDate?, val requestedDate: LocalDate) : PlanResult
        data class Failure(val reason: String) : PlanResult
    }

    fun planFastest(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long
    ): PlanResult {
        val runtimeSurface = File(context.filesDir, "runtime/surface")
        if (!File(runtimeSurface, "manifest.json").exists()) {
            return PlanResult.RuntimeMissing("Транспортные данные ещё не установлены")
        }

        return runCatching {
            SurfaceScheduleRepository(context).use { repository ->
                val serviceDate = repository.serviceDate
                    .takeIf { it.isNotBlank() }
                    ?.let(LocalDate::parse)
                    ?: return@use PlanResult.Failure("В runtime отсутствует service_date")

                val departureDate = Instant.ofEpochSecond(departureEpochSec)
                    .atZone(zoneId)
                    .toLocalDate()

                val serviceMidnight = serviceDate.atStartOfDay(zoneId).toEpochSecond()
                val serviceSeconds = (departureEpochSec - serviceMidnight).toInt()

                // Moscow service days may legitimately continue after midnight. We allow a runtime
                // to serve up to 30:00 of its service day, but never silently use it beyond that.
                if (serviceSeconds !in 0..MAX_SERVICE_SECONDS) {
                    return@use PlanResult.ScheduleUnavailable(serviceDate, departureDate)
                }

                val router = SurfaceCsaRouter(repository, preferences)
                val surface = router.findFastest(
                    origin = origin,
                    destination = destination,
                    departureServiceSec = serviceSeconds,
                    serviceMidnightEpochSec = serviceMidnight
                )
                PlanResult.Success(
                    fastest = RouteRanker.score(surface.fastest, RouteObjective.FASTEST, preferences),
                    serviceDate = serviceDate
                )
            }
        }.getOrElse { error ->
            PlanResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_SERVICE_SECONDS = 30 * 60 * 60
    }
}

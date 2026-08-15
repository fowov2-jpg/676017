package app.humanrouter.routing

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal data class SurfaceScheduleDecision(
    val usable: Boolean,
    val stale: Boolean
)

internal object SurfaceSchedulePolicy {
    private const val MAX_STALE_DAYS = 3L

    fun evaluate(serviceDate: LocalDate, requestedDate: LocalDate): SurfaceScheduleDecision {
        val distance = abs(ChronoUnit.DAYS.between(serviceDate, requestedDate))
        return SurfaceScheduleDecision(
            usable = distance <= MAX_STALE_DAYS,
            stale = serviceDate != requestedDate
        )
    }
}

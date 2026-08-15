package app.humanrouter.routing

import java.time.Instant
import java.time.ZoneId

internal object RailServiceWindow {
    private val moscowZone = ZoneId.of("Europe/Moscow")

    private const val METRO_SERVICE_START_SECONDS = 5 * 60 * 60 + 30 * 60
    private const val METRO_FIRST_TRAIN_LATEST_SECONDS = 6 * 60 * 60 + 5 * 60
    private const val METRO_SERVICE_END_SECONDS = 1 * 60 * 60
    const val METRO_EARLY_SERVICE_UNCERTAINTY_SECONDS = 35 * 60

    fun acceptsBoarding(mode: TransportMode, epochSec: Long): Boolean {
        if (mode != TransportMode.METRO) return true
        return acceptsMetroSecondOfDay(localSecondOfDay(epochSec))
    }

    fun metroBoundaryUncertaintySeconds(mode: TransportMode, epochSec: Long): Int {
        if (mode != TransportMode.METRO) return 0
        return metroBoundaryUncertaintyAtSecondOfDay(localSecondOfDay(epochSec))
    }

    fun acceptsMetroSecondOfDay(secondOfDay: Int): Boolean {
        require(secondOfDay in 0 until 24 * 60 * 60)
        return secondOfDay >= METRO_SERVICE_START_SECONDS || secondOfDay < METRO_SERVICE_END_SECONDS
    }

    fun metroBoundaryUncertaintyAtSecondOfDay(secondOfDay: Int): Int {
        require(secondOfDay in 0 until 24 * 60 * 60)
        return if (secondOfDay in METRO_SERVICE_START_SECONDS until METRO_FIRST_TRAIN_LATEST_SECONDS) {
            METRO_EARLY_SERVICE_UNCERTAINTY_SECONDS
        } else {
            0
        }
    }

    private fun localSecondOfDay(epochSec: Long): Int =
        Instant.ofEpochSecond(epochSec).atZone(moscowZone).toLocalTime().toSecondOfDay()
}

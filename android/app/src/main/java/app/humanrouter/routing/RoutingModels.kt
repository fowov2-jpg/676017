package app.humanrouter.routing

data class GeoPoint(val lat: Double, val lon: Double)

enum class TransportMode {
    WALK,
    BUS,
    TRAM,
    METRO,
    MCC,
    MCD,
    TRAIN;

    companion object {
        /** Runtime values are strict: an unknown mode must never silently become a bus. */
        fun fromRuntimeValue(value: String?): TransportMode? = when (value?.trim()?.uppercase()) {
            "WALK" -> WALK
            "BUS" -> BUS
            "TRAM" -> TRAM
            "METRO" -> METRO
            "MCC" -> MCC
            "MCD" -> MCD
            "TRAIN" -> TRAIN
            else -> null
        }
    }
}

data class RoutePlace(val id: String, val name: String, val point: GeoPoint)

data class RouteLeg(
    val mode: TransportMode,
    val from: RoutePlace,
    val to: RoutePlace,
    val departureEpochSec: Long,
    val arrivalEpochSec: Long,
    val lineId: String? = null,
    val lineName: String? = null,
    val waitSeconds: Int = 0,
    val walkMeters: Int = 0,
    val uncertaintySeconds: Int = 0,
    val realtimeConfidence: Double = 0.5,
    val transferBufferSeconds: Int = 0,
    val stopCount: Int = 0
) {
    init {
        require(arrivalEpochSec >= departureEpochSec)
        require(waitSeconds >= 0)
        require(walkMeters >= 0)
        require(uncertaintySeconds >= 0)
        require(stopCount >= 0)
        require(realtimeConfidence in 0.0..1.0)
    }
    val durationSeconds: Int get() = (arrivalEpochSec - departureEpochSec).toInt()
}

data class RouteCandidate(
    val id: String,
    val requestedDepartureEpochSec: Long,
    val legs: List<RouteLeg>
) {
    init { require(legs.isNotEmpty()) }
    val arrivalEpochSec: Long get() = legs.last().arrivalEpochSec
    val totalSeconds: Int get() = (arrivalEpochSec - requestedDepartureEpochSec).toInt().coerceAtLeast(0)
    val walkMeters: Int get() = legs.sumOf { it.walkMeters }
    val transitLegCount: Int get() = legs.count { it.mode != TransportMode.WALK }
    val transferCount: Int get() = (transitLegCount - 1).coerceAtLeast(0)
    val uncertaintySeconds: Int get() = legs.sumOf { it.uncertaintySeconds }
}

data class RoutePreferences(
    val walkingSpeedMetersPerSecond: Double = 1.35,
    val maxWalkMeters: Int = 3_500,
    val preferLessWalking: Boolean = false,
    val preferFewerTransfers: Boolean = false,
    val minimumSuggestedSavingSeconds: Int = 180,
    val replanCooldownSeconds: Int = 120
)

enum class RouteObjective { FASTEST, RELIABLE, LESS_WALKING, FEWER_TRANSFERS }

data class RankedRoute(
    val route: RouteCandidate,
    val objective: RouteObjective,
    val expectedArrivalEpochSec: Long,
    val reliableArrivalEpochSec: Long,
    val transferSuccessProbability: Double,
    val score: Double
)
